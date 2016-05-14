package com.tribbloids.spookystuff.dsl

import com.tribbloids.spookystuff.SpookyEnvSuite
import com.tribbloids.spookystuff.actions.Wget
import com.tribbloids.spookystuff.rdd.FetchedDataset
import com.tribbloids.spookystuff.row.{DataRow, Field, FetchedRow, SquashedFetchedRow}

/**
*  Created by peng on 12/3/14.
*/
class TestDSL extends SpookyEnvSuite {

  import com.tribbloids.spookystuff.dsl._

  lazy val pages = (
    Wget("http://www.wikipedia.org/") ~ 'page  :: Nil
  ).fetch(spooky).toArray

  lazy val row = SquashedFetchedRow(dataRows = Array(DataRow()), fetchedOpt = Some(pages))
    .extract(
      S"title".head.text ~ 'abc,
      S"title".head ~ 'def
    )
      .unsquash.head

  test("symbol as Expr"){
    assert('abc.apply(row) === "Wikipedia")
  }

  test("defaultAs should not rename an Alias") {
    val renamed = 'abc as 'name1
    assert(renamed.name == "name1")
    val renamed2 = renamed as 'name2
    assert(renamed2.name == "name2")
    val notRenamed = renamed defaultAs 'name2
    assert(notRenamed.name == "name1")
  }

  test("andThen"){
    val fun = 'abc.andThen(_.toString)
    assert(fun.name === "<function1>")
    assert(fun(row) === "Wikipedia")
  }

  test("andUnlift"){
    val fun = 'abc.andOptional(_.toString.headOption)
    assert(fun.name === "<function1>")
    assert(fun(row) === 'W')
  }

  test("double quotes in selector by attribute should work") {
    val pages = (
      Wget("http://www.wikipedia.org/") :: Nil
      ).fetch(spooky).toArray
    val row = SquashedFetchedRow(Array(DataRow()), fetchedOpt = Some(pages))
      .extract(S"""a[href*="wikipedia"]""".href ~ 'uri)
      .unsquash.head

    assert(row._1.get(Field("uri")).nonEmpty)
  }

  test("uri"){
    assert(S.uri.apply(row) contains "://www.wikipedia.org/")
    assert('page.uri.apply(row) contains "://www.wikipedia.org/")
    assert('def.uri.apply(row) contains "://www.wikipedia.org/")
  }

  test("string interpolation") {
    val expr = x"static ${'notexist}"
    assert(expr.lift.apply(row).isEmpty)
    assert(expr.orElse[FetchedRow, String]{case _ => " "}.apply(row) == " ")
  }

  test("SpookyContext can be cast to a blank PageRowRDD with empty schema") {
    val rdd = spooky: FetchedDataset
    assert(rdd.fields.isEmpty)
    assert(rdd.count() == 1)

    val plan = rdd.plan
    assert(plan.rdd() == spooky.blankSelfRDD)
    assert(plan.spooky != spooky) //configs should be deep copied
  }
}