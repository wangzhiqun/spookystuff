package org.apache.spark.ml.dsl.utils.metadata

import com.tribbloids.spookystuff.testutils.FunSpecx
import org.apache.hadoop.fs.Path

class MetadataSuite extends FunSpecx {

  val wellformed = Metadata(
    "int" -> 1,
    "double" -> 2.5,
    "itr" -> Seq("a", "b"),
    "map" -> Map("a" -> 1, "b" -> 2)
  )

  val malformed = Metadata(
    wellformed.self ++ Map(
      "path" -> new Path("file://home/dir/")
    )
  )
  val malformedFixed = malformed.copy(self = malformed.self.updated("path", "file://home/dir/"))

  val nested = Metadata(
    malformed.self ++ Map(
      "children" -> malformed.self
    )
  )
  val nestedFixed = Metadata(
    malformedFixed.self ++ Map(
      "children" -> malformedFixed.self
    )
  )

  it("wellformed <=> JSON") {
    val o = wellformed
    val json = o.toJSON()
    json.shouldBe(
      """
        |{
        |  "int" : 1,
        |  "double" : 2.5,
        |  "itr" : [ "a", "b" ],
        |  "map" : {
        |    "a" : 1,
        |    "b" : 2
        |  }
        |}
      """.stripMargin
    )
    val back: Metadata = Metadata.fromJSON(json)
    assert(o == back)
  }

  //TODO: report bug and fix
  ignore("malformed <=> JSON") {
    val o = malformed
    val json = o.toJSON()
    json.shouldBe(
      """
        |{
        |  "int" : 1,
        |  "double" : 2.5,
        |  "itr" : [ "a", "b" ],
        |  "map" : {
        |    "a" : 1,
        |    "b" : 2
        |  },
        |  "path" : "file://home/dir"
        |}
      """.stripMargin
    )
    val back: Metadata = Metadata.fromJSON(json)
    assert(malformedFixed == back)
  }

  //TODO: report bug and fix
  ignore("nested <=> JSON") {
    val o = nested
    val json = o.toJSON()
    json.shouldBe(
      """
        |{
        |  "int" : 1,
        |  "double" : 2.5,
        |  "itr" : [ "a", "b" ],
        |  "map" : {
        |    "a" : 1,
        |    "b" : 2
        |  },
        |  "path" : "file://home/dir",
        |  "children" : {
        |    "int" : 1,
        |    "double" : 2.5,
        |    "itr" : [ "a", "b" ],
        |    "map" : {
        |      "a" : 1,
        |      "b" : 2
        |    },
        |    "path" : "file://home/dir"
        |  }
        |}
      """.stripMargin
    )
    val back: Metadata = Metadata.fromJSON(json)
    assert(nestedFixed == back)
  }
}
