package com.tribbloids.spookystuff

import com.tribbloids.spookystuff.actions._
import com.tribbloids.spookystuff.dsl._
import com.tribbloids.spookystuff.row.Field

class TestSpookyContext extends SpookyEnvSuite{

  test("SpookyContext should be Serializable") {

    val spooky = this.spooky
    val src = spooky.sqlContext.sparkContext.parallelize(1 to 10)

    val res = src.map {
      v => spooky.hashCode() + v
    }.reduce(_ + _)
  }

  test("SpookyContext.dsl should be Serializable") {

    val spooky = this.spooky
    val src = spooky.sqlContext.sparkContext.parallelize(1 to 10)

    val res = src.map {
      v => spooky.dsl.hashCode() + v
    }.reduce(_ + _)
  }

  test("derived instances of a SpookyContext should have the same configuration") {

    val spooky = this.spooky
    spooky.conf.shareMetrics = false

    val rdd2 = spooky.create(Seq("dummy"))
    assert(!(rdd2.spooky eq spooky))

    val conf1 = spooky.conf.dirs.toJSON
    val conf2 = rdd2.spooky.conf.dirs.toJSON
    assert(conf1 == conf2)
  }

  test("derived instances of a SpookyContext should have the same configuration after it has been modified") {

    val spooky = this.spooky
    spooky.conf.shareMetrics = false
    spooky.conf.dirs.root = "s3://root"
    spooky.conf.dirs.cache = "hdfs://dummy"

    val rdd2 = spooky.create(Seq("dummy"))
    assert(!(rdd2.spooky eq spooky))

    val conf1 = spooky.conf.dirs.toJSON
    val conf2 = rdd2.spooky.conf.dirs.toJSON
    assert(conf1 == conf2)
  }

  test("each noInput should have independent metrics if sharedMetrics=false") {

    val spooky = this.spooky
    spooky.conf.shareMetrics = false

    val rdd1 = spooky
      .fetch(
        Wget(STATIC_WIKIPEDIA_URI)
      )
    rdd1.count()

    val rdd2 = spooky
      .fetch(
        Wget(STATIC_WIKIPEDIA_URI)
      )
      
    rdd2.count()

    assert(rdd1.spooky.metrics !== rdd2.spooky.metrics)
    assert(rdd1.spooky.metrics.pagesFetched.value === 1)
    assert(rdd2.spooky.metrics.pagesFetched.value === 1)
  }

  test("each noInput should have shared metrics if sharedMetrics=true") {

    val spooky = this.spooky
    spooky.conf.shareMetrics = true

    val rdd1 = spooky
      .fetch(
        Wget("http://www.wikipedia.org")
      )
    rdd1.count()

    val rdd2 = spooky
      .fetch(
        Wget("http://en.wikipedia.org")
      )
    rdd2.count()

    assert(rdd1.spooky.metrics.toJSON === rdd2.spooky.metrics.toJSON)
  }

  test("can create PageRow from String") {

    val spooky = this.spooky
    val rows = spooky.create(Seq("a", "b"))

    val data = rows.collect().flatMap(_.dataRows).map(_.data).toList
    assert(data == List(Map(Field("_") -> "a"), Map(Field("_") -> "b")))
  }

  test("can create PageRow from map[String, String]") {

    val spooky = this.spooky
    val rows = spooky.create(Seq(Map("1" -> "a"), Map("2" -> "b")))

    val data = rows.collect().flatMap(_.dataRows).map(_.data).toList
    assert(data == List(Map(Field("1") -> "a"), Map(Field("2") -> "b")))
  }

  test("can create PageRow from map[Symbol, String]") {

    val spooky = this.spooky
    val rows = spooky.create(Seq(Map('a1 -> "a"), Map('a2 -> "b")))

    val data = rows.collect().flatMap(_.dataRows).map(_.data).toList
    assert(data == List(Map(Field("a1") -> "a"), Map(Field("a2") -> "b")))
  }

  test("can create PageRow from map[Int, String]") {

    val spooky = this.spooky
    val rows = spooky.create(Seq(Map(1 -> "a"), Map(2 -> "b")))

    val data = rows.collect().flatMap(_.dataRows).map(_.data).toList
    assert(data == List(Map(Field("1") -> "a"), Map(Field("2") -> "b")))
  }

  test("default SpookyContext should have default dir configs") {

    val context = new SpookyContext(this.sql)

    val dirs = context.conf.dirs
    val json = dirs.toJSON
    println(json)

    import dirs._
    assert(!Seq(root, localRoot, autoSave, cache, errorDump, errorScreenshot, checkpoint, errorDumpLocal, errorScreenshotLocal).contains(null))
  }

  test("when sharedMetrics=false, new SpookyContext created from default SpookyConf should have default dir configs") {

    val conf: SpookyConf = new SpookyConf(shareMetrics = false)
    val context = new SpookyContext(this.sql, conf)

    val dirs = context.conf.dirs
    val json = dirs.toJSON
    println(json)

    import dirs._
    assert(!Seq(root, localRoot, autoSave, cache, errorDump, errorScreenshot, checkpoint, errorDumpLocal, errorScreenshotLocal).contains(null))
  }

  test("when sharedMetrics=true, new SpookyContext created from default SpookyConf should have default dir configs") {

    val conf: SpookyConf = new SpookyConf(shareMetrics = true)
    val context = new SpookyContext(this.sql, conf)

    val dirs = context.conf.dirs
    val json = dirs.toJSON
    println(json)

    import dirs._
    assert(!Seq(root, localRoot, autoSave, cache, errorDump, errorScreenshot, checkpoint, errorDumpLocal, errorScreenshotLocal).contains(null))
  }
}
