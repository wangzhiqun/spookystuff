package com.tribbloids.spookystuff.uav.planning

import java.io.File

import com.graphhopper.jsprit.analysis.toolbox.{AlgorithmSearchProgressChartListener, Plotter}
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm
import com.graphhopper.jsprit.core.algorithm.box.Jsprit
import com.graphhopper.jsprit.core.algorithm.recreate.VariableTransportCostCalculator
import com.graphhopper.jsprit.core.algorithm.state.{StateId, StateManager}
import com.graphhopper.jsprit.core.algorithm.termination.VariationCoefficientTermination
import com.graphhopper.jsprit.core.problem.constraint.{ConstraintManager, SoftActivityConstraint}
import com.graphhopper.jsprit.core.problem.job.Service
import com.graphhopper.jsprit.core.problem.solution.{SolutionCostCalculator, VehicleRoutingProblemSolution}
import com.graphhopper.jsprit.core.problem.vehicle.{VehicleImpl, VehicleTypeImpl}
import com.graphhopper.jsprit.core.problem.{Capacity, VehicleRoutingProblem, Location => JLocation}
import com.graphhopper.jsprit.core.reporting.SolutionPrinter
import com.graphhopper.jsprit.core.reporting.SolutionPrinter.Print
import com.graphhopper.jsprit.core.util.{Coordinate, FastVehicleRoutingTransportCostsMatrix, Solutions}
import com.tribbloids.spookystuff.SpookyContext
import com.tribbloids.spookystuff.actions.TraceView
import com.tribbloids.spookystuff.execution.ExecutionContext
import com.tribbloids.spookystuff.uav.UAVConf
import com.tribbloids.spookystuff.uav.actions.{UAVNavigation, Waypoint}
import com.tribbloids.spookystuff.uav.dsl.GenPartitioners
import com.tribbloids.spookystuff.uav.spatial.NED
import com.tribbloids.spookystuff.uav.telemetry.{LinkUtils, UAVStatus}
import org.apache.spark.rdd.RDD

object JSpritSolver extends MinimaxSolver {

  override def rewrite[V](
                           gp: GenPartitioners.MinimaxCost,
                           ec: ExecutionContext,
                           rdd: RDD[(TraceView, Iterable[V])]
                         ): RDD[(TraceView, Iterable[V])] = {

    val spooky = ec.spooky
    val linkRDD = LinkUtils.lockedLinkRDD(spooky)

    val allUAVs = linkRDD.map(v => v.status()).collect()
    val uavs = gp.numUAVOverride match {
      case Some(n) => allUAVs.slice(0, n)
      case None => allUAVs
    }

    val rows = rdd.collect()
    val solution = Solution(gp, spooky, uavs, rows)

    //TODO: this is where the monkey patch start, should avoid shipping V to drivers
    val uav2RowsMap: Map[UAVStatus, Seq[(TraceView, Iterable[V])]] =
      solution.getUAV2RowsMap

    val realignedRDD: RDD[(TraceView, Iterable[V])] = linkRDD.flatMap {
      link =>
        val status = link.status()
        val KVs = uav2RowsMap.getOrElse(status, Nil)
        val result = KVs.map {
          kv =>
            val vv = kv._1
            val updatedVV = vv.copy(
              children = List(PreferUAV(status,  Some(link._mutex.get._id)))
                ++ vv.children
            )
            updatedVV -> kv._2
        }
        result
    }
    realignedRDD
  }

  object Solution {

    def getObjectiveFunction(cohesiveness: Double): SolutionCostCalculator =
      new MinimiaxCost(cohesiveness)


    def getCostMatrix(
                       spooky: SpookyContext,
                       trace_indices: Seq[(TraceView, Int)]
                     ): FastVehicleRoutingTransportCostsMatrix = {

      val costEstimator = spooky.getConf[UAVConf].costEstimator

      val dMat = for (
        i <- trace_indices;
        j <- trace_indices
      ) yield {
        if (i._2 == j._2)
          (i._2, j._2, 0.0)
        else {
          val traceView: TraceView = i._1
          val last = traceView.children.collect { case v: UAVNavigation => v }.last
          val lastLocation = last._end
          val realTrace = List(Waypoint(lastLocation)) ++ j._1.children
          val cost = costEstimator.estimate(realTrace, spooky)
          (i._2, j._2, cost)
        }
      }

      val size = trace_indices.length
      val jRoutingCostMat: FastVehicleRoutingTransportCostsMatrix = {
        val builder = FastVehicleRoutingTransportCostsMatrix.Builder
          .newInstance(size, false)
        dMat.foreach {
          entry =>
            builder.addTransportTimeAndDistance(entry._1, entry._2, entry._3, entry._3)
        }
        builder.build()
      }
      jRoutingCostMat
    }


    def solveVRP(
                  vrp: VehicleRoutingProblem,
                  gp: GenPartitioners.MinimaxCost
                ): (VehicleRoutingProblemSolution, Double) = {

      val stateManager: StateManager = new StateManager(vrp)

      val stateId: StateId = stateManager.createStateId("max-transport-time")
      //introduce a new state called "max-transport-time"
      //add a default-state for "max-transport-time"
      //    stateManager.putProblemState(stateId, classOf[Double], 0.0)
      //
      stateManager.addStateUpdater(new MinimaxUpdater(stateManager, vrp, stateId))

      /*
       * The insertion heuristics is controlled with your constraints
       */
      val constraintManager: ConstraintManager = new ConstraintManager(vrp, stateManager)
      // soft constraint that calculates additional transport costs when inserting a job(activity) at specified position
      constraintManager.addConstraint(new VariableTransportCostCalculator(vrp.getTransportCosts, vrp.getActivityCosts))
      /*
       *  soft constraint that penalyzes a shift of max-route transport time, i.e. once the insertion heuristic
       *  tries to insert a jobActivity at position which results in a shift of max-transport-time, it is penalyzed with
       *  penaltyForEachTimeUnitAboveCurrentMaxTime
       *
       */
      val constraint: SoftActivityConstraint = new MinimaxConstraint(vrp, stateManager, stateId)
      constraintManager.addConstraint(constraint)

      val algorithmBuilder: Jsprit.Builder = Jsprit.Builder.newInstance(vrp)
      //		algorithmBuilder
      val objectiveFunction = Solution.getObjectiveFunction(gp.cohesiveness)
      algorithmBuilder.setObjectiveFunction(objectiveFunction)

      algorithmBuilder.setStateAndConstraintManager(stateManager, constraintManager)
      algorithmBuilder.addCoreStateAndConstraintStuff(true)

      val vra: VehicleRoutingAlgorithm = algorithmBuilder.buildAlgorithm

      gp.progressPlotPathOpt.foreach {
        v =>
          val file = new File(v)
          if (!file.exists()) file.getParentFile.mkdirs()
          vra.addListener(new AlgorithmSearchProgressChartListener(v))
      }
      val prematureAlgorithmTermination: VariationCoefficientTermination = new VariationCoefficientTermination(150, 0.001)
      vra.addListener(prematureAlgorithmTermination)
      vra.setPrematureAlgorithmTermination(prematureAlgorithmTermination)

      val solutions = vra.searchSolutions

      val best = Solutions.bestOf(solutions)

      SolutionPrinter.print(vrp, best, Print.VERBOSE)
      gp.solutionPlotPathOpt.foreach {
        v =>
          plot(vrp, best, v)
      }

      best -> objectiveFunction.getCosts(best)
    }

    def plot(
              vrp: VehicleRoutingProblem,
              solution: VehicleRoutingProblemSolution,
              path: String,
              title: String = "JSprit"
            ) = {

      val file = new File(path)
      if (!file.exists()) file.getParentFile.mkdirs()

      val plotter2: Plotter = new Plotter(vrp, solution)
      //		plotter2.setShowFirstActivity(true);

      plotter2.plot(path, title)
    }
  }

  case class Solution[V](
                          minimax: GenPartitioners.MinimaxCost,
                          spooky: SpookyContext,
                          uavs: Array[UAVStatus],
                          rows: Array[(TraceView, Iterable[V])]
                        ) {

    val traces = rows.map(_._1)

    val trace_uavOpt_index: Array[((TraceView, Option[UAVStatus]), Int)] = {
      val fromUAVs: Array[(TraceView, Option[UAVStatus])] =
        uavs.map {
          uav =>
            TraceView(List(Waypoint(uav.currentLocation))) -> Some(uav)
        }

      val fromTraces: Array[(TraceView, Option[UAVStatus])] = traces.map {
        trace =>
          trace -> None
      }

      (fromUAVs ++ fromTraces).zipWithIndex
    }

    val homeLocation = spooky.getConf[UAVConf].home

    lazy val define: VehicleRoutingProblem = {

      val trace_indices: Array[(TraceView, Int)] = trace_uavOpt_index.map {
        triplet =>
          triplet._1._1 -> triplet._2
      }

      val jRoutingCostMat: FastVehicleRoutingTransportCostsMatrix =
        Solution.getCostMatrix(spooky, trace_indices)

      val jVehicles: Array[VehicleImpl] = getJVehicles

      val jServices: Array[Service] = getJServices

      val vrp = {
        val builder = VehicleRoutingProblem.Builder.newInstance()
          .setRoutingCost(jRoutingCostMat)
        for (v <- jVehicles) {
          builder.addVehicle(v)
        }
        for (s <- jServices) {
          builder.addJob(s)
        }
        builder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE)
        builder.build()
      }
      vrp
    }

    def getJVehicles: Array[VehicleImpl] = {
      val cap = Capacity.Builder.newInstance()
        .addDimension(0, 1)
        .build()
      val jVType = VehicleTypeImpl.Builder.newInstance("UAV")
        .setCapacityDimensions(cap)
        .build()

      val jVehicles = trace_uavOpt_index
        .flatMap {
          triplet =>
            triplet._1._2.map { v => v -> triplet._2 }
        }
        .map {
          tuple =>
            val status = tuple._1
            val location = status.currentLocation
            val coord = location.getCoordinate(NED, homeLocation).get
            val jLocation = JLocation.Builder.newInstance()
              .setIndex(tuple._2)
              .setCoordinate(
                Coordinate.newInstance(
                  coord.east,
                  coord.north
                )
              )
              .build()
            val jVehicle = VehicleImpl.Builder
              .newInstance(status.uav.primaryURI)
              .setType(jVType)
              .setStartLocation(jLocation)
              .setReturnToDepot(false)
              .build()
            jVehicle
        }
      jVehicles
    }

    def getJServices: Array[Service] = {
      val jServices: Array[Service] = trace_uavOpt_index
        .flatMap {
          triplet =>
            triplet._1._2 match {
              case Some(_) =>
                None
              case None =>
                Some(triplet._1._1 -> triplet._2)
            }
        }
        .map {
          tuple =>
            val navs: Seq[UAVNavigation] = tuple._1.children.collect {
              case nav: UAVNavigation => nav
            }

            val coord = navs.head._start.getCoordinate(NED, homeLocation).get
            val location = JLocation.Builder
              .newInstance()
              .setIndex(tuple._2)
              .setCoordinate(
                Coordinate.newInstance(
                  coord.east,
                  coord.north
                )
              )
              .build()

            Service.Builder.newInstance(tuple._1.hashCode().toString)
              .setLocation(location)
              .build()
        }
      jServices
    }

    // TODO: need independent test
    // TODO: why not use shipment? has better visualization.
    lazy val solve: VehicleRoutingProblemSolution = {

      val vrp: VehicleRoutingProblem = define

      val tuple = Solution.solveVRP(vrp, minimax)
      println(s"cost = ${tuple._2}")
      tuple._1
    }

    lazy val getUAV2RowsMap: Map[UAVStatus, Seq[(TraceView, Iterable[V])]] = {

      import scala.collection.JavaConverters._

      val routes = solve.getRoutes.asScala.toList
      val status_KVs: Seq[(UAVStatus, List[(TraceView, Iterable[V])])] = routes.map {
        route =>
          val status = uavs.find(_.uav.primaryURI == route.getVehicle.getId).get
          val tours = route.getTourActivities.getActivities.asScala.toList
          val traces = for (tour <- tours) yield {
            val index = tour.getLocation.getIndex
            val trace: TraceView = trace_uavOpt_index.find(_._2 == index).get._1._1
            val v = rows.find(_._1 == trace).get._2
            trace -> v
          }
          status -> traces
      }
      val status_KVMap = Map(status_KVs: _*)
      status_KVMap
    }
  }
}