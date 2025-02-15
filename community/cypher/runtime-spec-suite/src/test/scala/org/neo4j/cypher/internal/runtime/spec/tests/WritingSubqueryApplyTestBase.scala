/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.spec.tests

import org.neo4j.cypher.internal.CypherRuntime
import org.neo4j.cypher.internal.RuntimeContext
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.createNode
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.createNodeWithProperties
import org.neo4j.cypher.internal.logical.plans.Ascending
import org.neo4j.cypher.internal.runtime.spec.Edition
import org.neo4j.cypher.internal.runtime.spec.LogicalQueryBuilder
import org.neo4j.cypher.internal.runtime.spec.RuntimeTestSuite

abstract class WritingSubqueryApplyTestBase[CONTEXT <: RuntimeContext](
  edition: Edition[CONTEXT],
  runtime: CypherRuntime[CONTEXT]
) extends RuntimeTestSuite[CONTEXT](edition, runtime) {

  test("should handle RHS with R/W dependencies - with AllNodesScan on RHS") {
    // given
    val sizeHint = 16
    val inputVals = (0 until sizeHint).toArray
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(1)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.eager()
      .|.allNodeScan("y", "x")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = inputVals.flatMap(i => (0 until Math.pow(2, i).toInt).map(_ => i))

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test("should handle RHS with R/W dependencies - with Filter (cancels rows) under Apply") {
    // given
    val sizeHint = 16
    val inputValsToCancel = (0 until sizeHint).map(_ => sizeHint).toArray
    val inputValsToPassThrough = (0 until sizeHint).toArray
    val inputVals = inputValsToCancel ++ inputValsToPassThrough ++ inputValsToCancel
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(1)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.eager()
      .|.create(createNode("n"))
      .|.argument("x")
      .filter(s"x <> $sizeHint")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = inputValsToPassThrough

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test("should handle RHS with R/W dependencies - with Filter under Apply and Sort on RHS") {
    // given
    val sizeHint = 16
    val inputValsToCancel = (0 until sizeHint).map(_ => sizeHint).toArray
    val inputValsToPassThrough = (0 until sizeHint).toArray
    val inputVals = inputValsToCancel ++ inputValsToPassThrough ++ inputValsToCancel
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(1)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.sort(Seq(Ascending("y")))
      .|.create(createNode("n"))
      .|.eager()
      .|.allNodeScan("y", "x")
      .filter(s"x <> $sizeHint")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = inputValsToPassThrough.flatMap(i => (0 until Math.pow(2, i).toInt).map(_ => i))

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test("should handle node creation in nested apply - with Filter on RHS") {
    // given
    val sizeHint = 16
    val inputValsToCancel = (0 until sizeHint).map(_ => sizeHint).toArray
    val inputValsToPassThrough = (0 until sizeHint).toArray
    val inputVals = inputValsToCancel ++ inputValsToPassThrough ++ inputValsToCancel
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(1)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.sort(Seq(Ascending("y")))
      .|.apply(fromSubquery = true)
      .|.|.top(Seq(Ascending("x")), 1)
      .|.|.deleteNode("m")
      .|.|.argument("x", "m")
      .|.create(createNode("m"))
      .|.create(createNode("n"))
      .|.eager()
      .|.allNodeScan("y", "x")
      .filter(s"x <> $sizeHint")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = inputValsToPassThrough.flatMap(i => (0 until Math.pow(2, i).toInt).map(_ => i))

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(
      nodesCreated = expected.length * 2,
      nodesDeleted = expected.length
    )
  }

  test("should handle RHS with R/W dependencies - with Argument on RHS") {
    // given
    val sizeHint = 16

    val nodes = given {
      nodeGraph(sizeHint)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.argument("x")
      .allNodeScan("x")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    val expected = nodes

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test("should handle RHS with R/W dependencies - with aggregation on top of Apply and AllNodesScan on RHS") {
    // given
    val sizeHint = 16
    val inputVals = (0 until sizeHint).toArray
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(1)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(x) AS c"))
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.eager()
      .|.allNodeScan("y", "x")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = inputVals.flatMap(i => (0 until Math.pow(2, i).toInt).map(_ => i)).length

    // then
    runtimeResult should beColumns("c").withSingleRow(expected).withStatistics(nodesCreated = expected)
  }

  test("should handle RHS with R/W dependencies - with aggregation on top of Apply and Argument on RHS") {
    // given
    val sizeHint = 16

    val nodes = given {
      nodeGraph(sizeHint)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(x) AS c"))
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.argument("x")
      .allNodeScan("x")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    val expected = nodes.length

    // then
    runtimeResult should beColumns("c").withSingleRow(expected).withStatistics(nodesCreated = expected)
  }

  test("should handle RHS with R/W dependencies on top of join and AllNodesScan on RHS") {
    // given
    val sizeHint = 16
    val inputVals = (0 until sizeHint).toArray
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(1)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.eager()
      .|.nodeHashJoin("y")
      .|.|.allNodeScan("y", "x")
      .|.allNodeScan("y", "x")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = inputVals.flatMap(i => (0 until Math.pow(2, i).toInt).map(_ => i))

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test("should handle RHS with R/W dependencies on top of join and Argument on RHS") {
    // given
    val sizeHint = 16

    val nodes = given {
      nodeGraph(sizeHint)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.nodeHashJoin("x")
      .|.|.argument("x")
      .|.argument("x")
      .allNodeScan("x")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    val expected = nodes

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test(
    "should handle RHS with R/W dependencies on top of join - with aggregation on top of Apply and AllNodesScan on RHS"
  ) {
    // given
    val sizeHint = 16
    val inputVals = (0 until sizeHint).toArray
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(1)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(x) AS c"))
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.eager()
      .|.nodeHashJoin("y")
      .|.|.allNodeScan("y", "x")
      .|.allNodeScan("y", "x")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = inputVals.flatMap(i => (0 until Math.pow(2, i).toInt).map(_ => i)).length

    // then
    runtimeResult should beColumns("c").withSingleRow(expected).withStatistics(nodesCreated = expected)
  }

  test(
    "should handle RHS with R/W dependencies on top of join - with aggregation on top of Apply and Argument on RHS"
  ) {
    // given
    val sizeHint = 16

    val nodes = given {
      nodeGraph(sizeHint)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(x) AS c"))
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.nodeHashJoin("x")
      .|.|.argument("x")
      .|.argument("x")
      .allNodeScan("x")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    val expected = nodes.length

    // then
    runtimeResult should beColumns("c").withSingleRow(expected).withStatistics(nodesCreated = expected)
  }

  test("should handle RHS with R/W dependencies on top of union - with AllNodesScan on RHS") {
    // given
    val sizeHint = 4
    val initialNodeCount = 1
    val inputVals = (0 until sizeHint).toArray
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(initialNodeCount)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.eager()
      .|.union()
      .|.|.allNodeScan("y", "x")
      .|.allNodeScan("y", "x")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = {
      var nodeCount = initialNodeCount
      for {
        inputVal <- inputVals
      } yield {
        val unionOutputRows = nodeCount * 2
        val inputValRepetitions = (0 until unionOutputRows).map(_ => inputVal)
        nodeCount += inputValRepetitions.size
        inputValRepetitions
      }
    }.flatten

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test("should handle RHS with R/W dependencies on top of union - with Argument on RHS") {
    // given
    val sizeHint = 4

    val nodes = given {
      nodeGraph(sizeHint)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.union()
      .|.|.argument("x")
      .|.argument("x")
      .allNodeScan("x")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    val expected = nodes.flatMap(node => Seq(node, node))

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test(
    "should handle RHS with R/W dependencies on top of union - with aggregation on top of Apply and AllNodesScan on RHS"
  ) {
    // given
    val sizeHint = 4
    val initialNodeCount = 1
    val inputVals = (0 until sizeHint).toArray
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(initialNodeCount)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(x) AS c"))
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.eager()
      .|.union()
      .|.|.allNodeScan("y", "x")
      .|.allNodeScan("y", "x")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = {
      var nodeCount = initialNodeCount
      for {
        inputVal <- inputVals
      } yield {
        val unionOutputRows = nodeCount * 2
        val inputValRepetitions = (0 until unionOutputRows).map(_ => inputVal)
        nodeCount += inputValRepetitions.size
        inputValRepetitions
      }
    }.flatten.length

    // then
    runtimeResult should beColumns("c").withSingleRow(expected).withStatistics(nodesCreated = expected)
  }

  test(
    "should handle RHS with R/W dependencies on top of union - with aggregation on top of Apply and Argument on RHS"
  ) {
    // given
    val sizeHint = 4

    val nodes = given {
      nodeGraph(sizeHint)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(x) AS c"))
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.union()
      .|.|.argument("x")
      .|.argument("x")
      .allNodeScan("x")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    val expected = nodes.length * 2

    // then
    runtimeResult should beColumns("c").withSingleRow(expected).withStatistics(nodesCreated = expected)
  }

  test("should handle RHS with R/W dependencies on top of cartesian product - with AllNodesScan on RHS") {
    // given
    val sizeHint = 4
    val initialNodeCount = 1
    val inputVals = (0 until sizeHint).toArray
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(initialNodeCount)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.eager()
      .|.cartesianProduct()
      .|.|.allNodeScan("z", "x")
      .|.allNodeScan("y", "x")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = {
      var nodeCount = initialNodeCount
      for {
        inputVal <- inputVals
      } yield {
        val cartesianProductOutputRows = nodeCount * nodeCount
        val inputValRepetitions = (0 until cartesianProductOutputRows).map(_ => inputVal)
        nodeCount += inputValRepetitions.size
        inputValRepetitions
      }
    }.flatten

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test("should handle RHS with R/W dependencies on top of cartesian product - with Argument on RHS") {
    // given
    val sizeHint = 4

    val nodes = given {
      nodeGraph(sizeHint)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.cartesianProduct()
      .|.|.argument("x")
      .|.argument("x")
      .allNodeScan("x")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    val expected = nodes

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test(
    "should handle RHS with R/W dependencies on top of cartesian product - with aggregation on top of Apply and AllNodesScan on RHS"
  ) {
    // given
    val sizeHint = 4
    val initialNodeCount = 1
    val inputVals = (0 until sizeHint).toArray
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(initialNodeCount)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(x) AS c"))
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.eager()
      .|.cartesianProduct()
      .|.|.allNodeScan("z", "x")
      .|.allNodeScan("y", "x")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = {
      var nodeCount = initialNodeCount
      for {
        inputVal <- inputVals
      } yield {
        val cartesianProductOutputRows = nodeCount * nodeCount
        val inputValRepetitions = (0 until cartesianProductOutputRows).map(_ => inputVal)
        nodeCount += inputValRepetitions.size
        inputValRepetitions
      }
    }.flatten.length

    // then
    runtimeResult should beColumns("c").withSingleRow(expected).withStatistics(nodesCreated = expected)
  }

  test(
    "should handle RHS with R/W dependencies on top of cartesian product - with aggregation on top of Apply and Argument on RHS"
  ) {
    // given
    val sizeHint = 4

    val nodes = given {
      nodeGraph(sizeHint)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(x) AS c"))
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.cartesianProduct()
      .|.|.argument("z", "x")
      .|.argument("y", "x")
      .allNodeScan("x")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    val expected = nodes.length

    // then
    runtimeResult should beColumns("c").withSingleRow(expected).withStatistics(nodesCreated = expected)
  }

  test("should handle RHS with R/W dependencies on top of nested unions - with AllNodesScan on RHS") {
    // given
    val sizeHint = 4
    val initialNodeCount = 1
    val inputVals = (0 until sizeHint).toArray
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(initialNodeCount)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.eager()
      .|.union()
      .|.|.union()
      .|.|.|.limit(1)
      .|.|.|.allNodeScan("y", "x")
      .|.|.allNodeScan("y", "x")
      .|.allNodeScan("y", "x")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = {
      var nodeCount = initialNodeCount
      for {
        inputVal <- inputVals
      } yield {
        val unionOutputRows = nodeCount * 2 + 1
        val inputValRepetitions = (0 until unionOutputRows).map(_ => inputVal)
        nodeCount += inputValRepetitions.size
        inputValRepetitions
      }
    }.flatten

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test("should handle RHS with R/W dependencies on top of nested unions - with Argument on RHS") {
    // given
    val sizeHint = 4

    val nodes = given {
      nodeGraph(sizeHint)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.union()
      .|.|.union()
      .|.|.|.limit(1)
      .|.|.|.argument("x")
      .|.|.argument("x")
      .|.argument("x")
      .allNodeScan("x")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    val expected = nodes.flatMap(node => Seq(node, node, node))

    // then
    runtimeResult should beColumns("x").withRows(singleColumn(expected)).withStatistics(nodesCreated = expected.length)
  }

  test(
    "should handle RHS with R/W dependencies on top of nested unions - with aggregation on top of Apply and AllNodesScan on RHS"
  ) {
    // given
    val sizeHint = 4
    val initialNodeCount = 1
    val inputVals = (0 until sizeHint).toArray
    val input = inputValues(inputVals.map(Array[Any](_)): _*)

    given {
      nodeGraph(initialNodeCount)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(x) AS c"))
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.eager()
      .|.union()
      .|.|.union()
      .|.|.|.limit(1)
      .|.|.|.allNodeScan("y", "x")
      .|.|.allNodeScan("y", "x")
      .|.allNodeScan("y", "x")
      .input(variables = Seq("x"))
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime, input)
    consume(runtimeResult)

    val expected = {
      var nodeCount = initialNodeCount
      for {
        inputVal <- inputVals
      } yield {
        val unionOutputRows = nodeCount * 2 + 1
        val inputValRepetitions = (0 until unionOutputRows).map(_ => inputVal)
        nodeCount += inputValRepetitions.size
        inputValRepetitions
      }
    }.flatten.length

    // then
    runtimeResult should beColumns("c").withSingleRow(expected).withStatistics(nodesCreated = expected)
  }

  test(
    "should handle RHS with R/W dependencies on top of nested unions - with aggregation on top of Apply and Argument on RHS"
  ) {
    // given
    val sizeHint = 4

    val nodes = given {
      nodeGraph(sizeHint)
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(x) AS c"))
      .apply(fromSubquery = true)
      .|.create(createNode("n"))
      .|.union()
      .|.|.union()
      .|.|.|.limit(1)
      .|.|.|.argument("x")
      .|.|.argument("x")
      .|.argument("x")
      .allNodeScan("x")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    val expected = nodes.length * 3

    // then
    runtimeResult should beColumns("c").withSingleRow(expected).withStatistics(nodesCreated = expected)
  }

  test("should handle node creation in deeply nested apply") {
    val sizeHint = 4
    val nodes = given {
      nodeGraph(sizeHint)
    }

    val logicalQuery0 = new LogicalQueryBuilder(this)
      .produceResults("n")
      .create(createNode("a", "Label")) // 6
      .apply(fromSubquery = true)
      .|.create(createNode("a", "Label")) // 5
      .|.apply(fromSubquery = true)
      .|.|.create(createNode("a", "Label")) // 4
      .|.|.apply(fromSubquery = true)
      .|.|.|.create(createNode("a", "Label")) // 3
      .|.|.|.apply(fromSubquery = true)
      .|.|.|.|.create(createNode("a", "Label")) // 2
    val logicalQuery = logicalQuery0
      .|.|.|.|.apply(fromSubquery = true)
      .|.|.|.|.|.create(createNode("a", "Label")) // 1
      .|.|.|.|.|.argument("n")
      .|.|.|.|.argument("n")
      .|.|.|.argument("n")
      .|.|.argument("n")
      .|.argument("n")
      .allNodeScan("n")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    // then
    runtimeResult should beColumns("n")
      .withStatistics(nodesCreated = 6 * sizeHint, labelsAdded = 6 * sizeHint)
      .withRows(nodes.map(Array[Any](_)))
  }

  test("should handle nested apply and exhaustive limit") {
    val sizeHint = 4
    given {
      nodeGraph(sizeHint)
    }

    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("n")
      .exhaustiveLimit(0)
      .create(createNode("a", "Label"))
      .apply(fromSubquery = true)
      .|.exhaustiveLimit(0)
      .|.create(createNode("a", "Label"))
      .|.apply(fromSubquery = true)
      .|.|.exhaustiveLimit(0)
      .|.|.create(createNode("a", "Label")) // only here nodes will be created
      .|.|.argument("n")
      .|.argument("n")
      .allNodeScan("n")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    // then
    runtimeResult should beColumns("n")
      .withStatistics(nodesCreated = sizeHint, labelsAdded = sizeHint)
      .withNoRows()
  }

  test("should handle nested apply and unwind") {
    val sizeHint = 4

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("xmax", "xmin")
      .apply(fromSubquery = true)
      .|.apply(fromSubquery = true)
      .|.|.aggregation(Seq(), Seq("min(x) AS xmin"))
      .|.|.create(createNodeWithProperties("anon_1", Seq("Label_2"), "{id: x}"))
      .|.|.argument("x")
      .|.apply(fromSubquery = true)
      .|.|.aggregation(Seq(), Seq("max(x) AS xmax"))
      .|.|.create(createNodeWithProperties("anon_0", Seq("Label"), "{id: x}"))
      .|.|.argument("x")
      .|.argument("x")
      .unwind(s"range(0, ${sizeHint - 1}) AS x")
      .argument()
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    // then
    runtimeResult should beColumns("xmax", "xmin")
      .withStatistics(nodesCreated = sizeHint * 2, propertiesSet = sizeHint * 2, labelsAdded = sizeHint * 2)
      .withRows(rowCount(sizeHint))
  }

  test("should handle nested apply and exhaustive limit and skip") {
    val sizeHint = 4
    given {
      nodeGraph(sizeHint)
    }

    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("n")
      .skip(sizeHint)
      .exhaustiveLimit(add(literalInt(1), literalInt(2)))
      .create(createNode("a", "Label")) // 3
      .apply(fromSubquery = true)
      .|.create(createNode("a", "Label")) // 2
      .|.apply(fromSubquery = true)
      .|.|.create(createNode("a", "Label")) // 1
      .|.|.argument("n")
      .|.argument("n")
      .allNodeScan("n")
      .build(readOnly = false)

    val runtimeResult = execute(logicalQuery, runtime)
    consume(runtimeResult)

    // then
    runtimeResult should beColumns("n")
      .withStatistics(nodesCreated = 3 * sizeHint, labelsAdded = 3 * sizeHint)
      .withNoRows
  }
}
