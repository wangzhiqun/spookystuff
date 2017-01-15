package com.tribbloids.spookystuff.mav.telemetry

import com.tribbloids.spookystuff.mav.actions._
import com.tribbloids.spookystuff.mav.dsl.{LinkFactories, LinkFactory}
import com.tribbloids.spookystuff.mav.{MAVConf, ReinforcementDepletedException}
import com.tribbloids.spookystuff.session.python._
import com.tribbloids.spookystuff.session.{Cleanable, LocalCleanable, ResourceLock, Session}
import com.tribbloids.spookystuff.utils.SpookyUtils
import com.tribbloids.spookystuff.{PyInterpreterException, SpookyContext, caching}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Try}

case class Endpoint(
                     uri: String, // [protocol]:ip:port;[baudRate]
                     baudRate: Int = MAVConf.DEFAULT_BAUDRATE,
                     ssid: Int = MAVConf.EXECUTOR_SSID,
                     frame: Option[String] = None
                   ) extends CaseInstanceRef with SingletonRef with LocalCleanable with ResourceLock {

  override lazy val resourceIDs = Map("" -> Set(uri))
}

object Link {

  def cleanSanityCheck(): Unit = {
    val subs = Cleanable.getTyped[Endpoint]() ++ Cleanable.getTyped[Proxy]()
    val refSubs = Cleanable.getTyped[Link]().flatMap(_.subCleanable)
    assert(
      subs.intersect(refSubs).size <= refSubs.size,
      {
        "INTERNAL ERROR: dangling tree!"
      }
    )
  }

  // max 1 per task/thread.
  val driverLocal: caching.ConcurrentMap[PythonDriver, Link] = caching.ConcurrentMap()

  // connStr -> (link, isBusy)
  // only 1 allowed per connStr, how to enforce?
  val existing: caching.ConcurrentMap[Drone, Link] = caching.ConcurrentMap()

  // won't be used to create any link before its status being recovered by ping daemon.
  val blacklist: caching.ConcurrentSet[Drone] = caching.ConcurrentSet()

  def getOrInitialize(
                       candidates: Seq[Drone],
                       factory: LinkFactory,
                       session: Session,
                       locationOpt: Option[Location] = None
                     ): Link = {

    session.initializeDriverIfMissing {
      getOrCreate(candidates, factory, session, locationOpt)
    }
  }

  /**
    * create a telemetry link based on the following order:
    * if one is already created in the same task, reuse it
    * if one is created in a previous task and not busy, use it. The busy status is controlled by whether it has an active python driver.
    *   - if its generated by an obsolete ProxyFactory, terminate the link and immediately recreate a new one with the new ProxyFactory,
    *     being created means the drone is already in the air, and can be deployed much faster
    * * if multiple are created by previous tasks and not busy, use the one that is closest to the first waypoint * (not implemented yet)
    * If none of the above exists, create one from candidates from scratch
    * remember: once the link is created its proxy is bind to it until death.
    */
  def getOrCreate(
                   candidates: Seq[Drone],
                   factory: LinkFactory,
                   session: Session,
                   locationOpt: Option[Location] = None
                 ): Link = {

    val local = driverLocal
      .get(session.pythonDriver)

    local.foreach {
      link =>
        LoggerFactory.getLogger(this.getClass).info(
          s"Using existing Link ${link.drone} with the same driver"
        )
    }

    val result = local
      .getOrElse {
        val newLink = recommissionIdle(candidates, factory, session.spooky, locationOpt)
          .getOrElse {
            selectAndCreate(candidates, factory, session.spooky)
          }
        try {
          newLink.Py(session)
        }
        catch {
          case e: Throwable =>
            newLink.clean()
            throw e
        }

        newLink
      }
    result
  }

  // CAUTION: this will refit the telemetry link with new Proxy and clean the old one if ProxyFactory is different.
  def recommissionIdle(
                        candidates: Seq[Drone],
                        factory: LinkFactory,
                        spooky: SpookyContext,
                        locationOpt: Option[Location] = None
                      ): Option[Link] = {

    val result = this.synchronized {
      val existingCandidates: Seq[Link] = candidates.collect {
        Function.unlift {
          endpoint =>
            existing.get(endpoint)
        }
      }

      val idleLinks = existingCandidates.filter {
        link =>
          link.isIdle
      }

      //TODO: find the closest one!
      val idleLinkOpt = idleLinks.headOption

      idleLinkOpt match {
        case Some(idleLink) =>
          val recommissioned = {
            if (LinkFactories.canCreate(factory, idleLink)) {
              idleLink.onHold = true
              LoggerFactory.getLogger(this.getClass).info {
                s"Recommissioning telemetry for ${idleLink.drone} with existing proxy"
              }
              idleLink
            }
            else {
              idleLink.clean()
              // recreate proxy
              val link = factory.apply(idleLink.drone)
              link.onHold = true
              LoggerFactory.getLogger(this.getClass).info {
                s"Existing proxy for ${link.drone} is obsolete, " +
                  s"will recreate proxy using ${factory.getClass.getSimpleName} and recommission telemetry"
              }
              link.wContext(
                spooky,
                factory
              )
            }
          }

          Some(recommissioned)
        case None =>
          val info = if (existingCandidates.isEmpty) {
            val msg = s"No existing telemetry Link for ${candidates.mkString("[", ", ", "]")}, existing links are:"
            val hint = Link.existing.keys.toList.mkString("[", ", ", "]")
            msg + "\n" + hint
          }
          else {
            existingCandidates.map {
              link =>
                assert(!link.isIdle)
                if (link.onHold) s"${link.drone} is on hold"
                else s"${link.drone} is busy"
            }
              .mkString("\n")
          }
          LoggerFactory.getLogger(this.getClass).info{info}
          None
      }
    }

    result
  }

  def selectAndCreate(
                       candidates: Seq[Drone],
                       factory: LinkFactory,
                       spooky: SpookyContext
                     ): Link = {

    val newLink = this.synchronized {
      val endpointOpt = candidates.find {
        v =>
          !existing.contains(v) &&
            !blacklist.contains(v)
      }
      val endpoint = endpointOpt
        .getOrElse(
          throw new ReinforcementDepletedException(
            candidates.map {
              candidate =>
                if (blacklist.contains(candidate)) s"$candidate is unreachable"
                else s"$candidate is busy"
            }
              .mkString(", ")
          )
        )

      create(endpoint, factory, spooky)
    }
    newLink
  }

  def create(
              endpoint: Drone,
              factory: LinkFactory,
              spooky: SpookyContext
            ): Link = {

    val link = factory.apply(endpoint)
    link.onHold = true
    link.wContext(
      spooky,
      factory
    )
  }

  //TODO: this entire big thing should be delegated to Endpoint
  class PyBindingImpl(
                       override val ref: Link,
                       override val driver: PythonDriver,
                       override val spookyOpt: Option[SpookyContext]
                     ) extends com.tribbloids.spookystuff.session.python.PyBinding(ref, driver, spookyOpt) {

    $Helpers.autoStart()
    Link.driverLocal += driver -> ref

    override def cleanImpl(): Unit = {
      super.cleanImpl()
      val localOpt = Link.driverLocal.get(driver)
      localOpt.foreach {
        v =>
          if (v eq this.ref)
            Link.driverLocal -= driver
      }
    }

    object $Helpers {
      var isStarted: Boolean = false

      def _startDaemons(): Unit = {
        if (!isStarted) {
          ref.proxyOpt.foreach {
            _.PY.start()
          }
          ref.Endpoints.primary._Py(driver, spookyOpt).start()
        }
        isStarted = true
      }

      def stopDaemons(): Unit = {
        ref.Endpoints.primary._Py(driver, spookyOpt).stop()
        ref.proxyOpt.foreach {
          _.PY.stop()
        }
        isStarted = false
      }

      def withDaemonsUp[T](fn: => T) = {
        try {
          _startDaemons()
          fn
        }
        catch {
          case e: Throwable =>
            stopDaemons()
            throw e
        }
      }

      // will retry 6 times, try twice for Vehicle.connect() in python, if failed, will restart proxy and try again (3 times).
      // after all attempts failed will stop proxy and add endpoint into blacklist.
      def autoStart(): Unit = try {
        val retries = spookyOpt.map(
          spooky =>
            spooky.conf.submodule[MAVConf].connectionRetries
        )
          .getOrElse(MAVConf.CONNECTION_RETRIES)
        SpookyUtils.retry(retries) {
          withDaemonsUp(Unit)
        }
      }
      catch {
        case e: PyInterpreterException =>
          try {
            val extra: Seq[Throwable] = {
              Seq(
                Try(PyRef.cleanSanityCheck()),
                Try(Link.cleanSanityCheck())
              ) ++ Option(e.cause).map(v => Failure(v))
            }
              .collect {
                case Failure(ee) => ee
              }
            ResourceLock.detectConflict(extra)
          }
          catch {
            case ee: Throwable =>
              throw e.copy(
                cause = ee
              )
          }
          val withExtra = e.copy(code = e.code + "\n\n\t### No port conflict detected ###")
          withExtra.setStackTrace(e.getStackTrace)
          throw withExtra
      }
    }
  }
}

/**
to keep a drone in the air, a python daemon process D has to be constantly running to
supervise task-irrelevant path planning (e.g. RTL/Position Hold/Avoidance).
This process outlives each task. Who launches D? how to ensure smooth transitioning
of control during Partition1 => D => Partition2 ? Can they share the same
Connection / Endpoint / Proxy ? Do you have to make them picklable ?

GCS:UDP:xxx ------------------------> Proxy:TCP:xxx -> Drone
                                   /
TaskProcess -> Connection:UDP:xx -/
            /
DaemonProcess   (can this be delayed to be implemented later? completely surrender control to GCS after Altitude Hold)
  is Vehicle picklable? if yes then that changes a lot of things.
  but if not ...
    how to ensure that an interpreter can takeover and get the same vehicle?
  */
case class Link(
                 drone: Drone,
                 executorOuts: Seq[String] = Nil, // cannot have duplicates
                 gcsOuts: Seq[String] = Nil
               ) extends NoneRef with SingletonRef with LocalCleanable with ResourceLock {

  {
    if (executorOuts.isEmpty) assert(gcsOuts.isEmpty, "No endpoint for executor")
  }

  val outs: Seq[String] = executorOuts ++ gcsOuts
  val allURIs = (drone.uris ++ outs).distinct

  /**
    * CAUTION: ALL of them have to be val or lazy val! Or you risk recreating many copies each with its own python! Conflict with each other!
    */
  object Endpoints {
    val direct: Endpoint = drone.getDirectEndpoint
    val executor = if (executorOuts.isEmpty) {
      Seq(direct)
    }
    else {
      executorOuts.map {
        out =>
          direct.copy(uri = out)
      }
    }
    //always initialized in Python when created from companion object
    val primary: Endpoint = executor.head
    val gcs = {
      gcsOuts.map {
        out =>
          direct.copy(uri = out)
      }
    }
    val all: Seq[Endpoint] = (Seq(direct) ++ executor ++ gcs).distinct
  }

  override lazy val resourceIDs = Map("" -> (drone.uris ++ executorOuts).toSet)

  import Endpoints._

  override type Binding = Link.PyBindingImpl

  /**
    * set true to block being used by another thread before its driver is created
    */
  @volatile var onHold: Boolean = true
  def isIdle: Boolean = {
    !onHold && driverToBindingsAlive.isEmpty
  }

  //mnemonic
  @volatile private var _proxyOpt: Option[Proxy] = None
  def proxyOpt: Option[Proxy] = _proxyOpt.orElse {
    this.synchronized {
      _proxyOpt = if (outs.isEmpty) None
      else {
        val proxy = Proxy(
          direct.uri,
          outs,
          direct.baudRate,
          name = drone.name
        )
        Some(proxy)
      }
      _proxyOpt
    }
  }

  var spookyOpt: Option[SpookyContext] = None
  var factoryOpt: Option[LinkFactory] = None
  def wContext(
                spooky: SpookyContext,
                factory: LinkFactory
              ): this.type = {

    try {
      spookyOpt = Option(spooky)
      factoryOpt = Option(factory)
      spooky.metrics.linkCreated += 1
      Link.existing += drone -> this

      this
    }
    catch {
      case e: Throwable =>
        this.clean()
        throw e
    }
  }

  override protected def newPy(driver: PythonDriver, spookyOpt: Option[SpookyContext]): Link.PyBindingImpl = {
    val result = new Link.PyBindingImpl(this, driver, spookyOpt)
    onHold = false
    result
  }

  //  def detectPortConflicts(causes: Seq[Throwable] = Nil): Unit = {
  //    val existing = Link.existing.values.toList.map(_.link) // remember to clean up the old one to create a new one
  //    val notThis = existing.filterNot(_ eq this)
  //    val includeThis: Seq[Link] = notThis ++ Seq(this)
  //    val s1 = {
  //      val ss1 = Seq(
  //        Try(assert(
  //          Link.existing.get(drone).forall(_.link eq this),
  //          s"Conflict: endpoint index ${direct.uri} is already used")
  //        ),
  //        Try(assert(
  //          !notThis.exists(_.Endpoints.direct.uri == direct.uri),
  //          s"Conflict: endpoint ${direct.uri} is already used")
  //        )
  //      )
  //      val allConnStrs: Map[String, Int] = includeThis.flatMap(_.drone.uris)
  //        .groupBy(identity)
  //        .mapValues(_.size)
  //      val ss2 = allConnStrs.toSeq.map {
  //        tuple =>
  //          Try(assert(tuple._2 == 1, s"${tuple._2} endpoints has identical uri ${tuple._1}"))
  //      }
  //      val ss3 = Seq(
  //        Try(PyRef.cleanSanityCheck()),
  //        Try(Link.cleanSanityCheck())
  //      )
  //      ss1 ++ ss2
  //    }
  //    val allExecutorOuts: Map[String, Int] = includeThis.flatMap(_.executorOuts)
  //      .groupBy(identity)
  //      .mapValues(_.size)
  //    val s = s1 ++ allExecutorOuts.toSeq.map {
  //      tuple =>
  //        Try(assert(tuple._2 == 1, s"${tuple._2} executor out has identical uri ${tuple._1}"))
  //    }
  //
  //    TreeException.&&&(s, extra = causes)
  //  }

  def getLocation: LocationGlobal = {

    val locations = primary.PY.vehicle.location
    val global = locations.global_frame.$MSG.get.cast[LocationGlobal]
    drone.lastLocation = Some(global)
    global
  }

  var isDryrun = false
  //finalizer may kick in and invoke it even if its in Link.existing

  override def subCleanable: Seq[Cleanable] = {
    all ++
      _proxyOpt.toSeq ++
      super.subCleanable
  }

  override protected def cleanImpl(): Unit = {

    super.cleanImpl()

    val existingOpt = Link.existing.get(drone)
    existingOpt.foreach {
      v =>
        if (v eq this)
          Link.existing -= drone
        else {
          if (!isDryrun) throw new AssertionError("THIS IS NOT A DRYRUN OBJECT! SO ITS CREATED ILLEGALLY!")
        }
    }
    spookyOpt.foreach {
      spooky =>
        spooky.metrics.linkDestroyed += 1
    }
  }
}