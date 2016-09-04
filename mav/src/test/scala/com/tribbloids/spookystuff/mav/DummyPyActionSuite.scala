package com.tribbloids.spookystuff.mav

import com.tribbloids.spookystuff.SpookyEnvFixture
import com.tribbloids.spookystuff.doc.Doc
import com.tribbloids.spookystuff.mav.actions.DummyPyAction

/**
  * Created by peng on 01/09/16.
  */
class DummyPyActionSuite extends SpookyEnvFixture {

  val action = DummyPyAction()

  test("can execute on driver") {

    val doc = action.fetch(spooky)
    doc.flatMap(_.asInstanceOf[Doc].code).mkString("\n").shouldBe(
      """
        |{"a": 1, "c": 3, "b": 2, "num-children": 0, "class": "com.tribbloids.spookystuff.mav.actions.DummyPyAction"}
      """.trim.stripMargin
    )
  }

  test("can execute on workers") {

  }
}