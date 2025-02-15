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
package org.neo4j.cypher.internal.compiler.planner.logical.steps.index

import org.neo4j.cypher.internal.ast.Hint
import org.neo4j.cypher.internal.compiler.planner.logical.LeafPlanRestrictions
import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.planner.logical.steps.RelationshipLeafPlanner.planHiddenSelectionAndRelationshipLeafPlan
import org.neo4j.cypher.internal.compiler.planner.logical.steps.index.EntityIndexScanPlanProvider.isAllowedByRestrictions
import org.neo4j.cypher.internal.compiler.planner.logical.steps.index.RelationshipIndexLeafPlanner.RelationshipIndexMatch
import org.neo4j.cypher.internal.expressions.Contains
import org.neo4j.cypher.internal.expressions.EndsWith
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.ir.PatternRelationship
import org.neo4j.cypher.internal.logical.plans.LogicalPlan

object RelationshipIndexStringSearchScanPlanProvider extends RelationshipIndexPlanProvider {

  override def createPlans(
    indexMatches: Set[RelationshipIndexMatch],
    hints: Set[Hint],
    argumentIds: Set[String],
    restrictions: LeafPlanRestrictions,
    context: LogicalPlanningContext
  ): Set[LogicalPlan] = for {
    indexMatch <- indexMatches
    if isAllowedByRestrictions(indexMatch.variableName, restrictions) && indexMatch.indexDescriptor.properties.size == 1
    plan <- doCreatePlans(indexMatch, hints, argumentIds, context)
  } yield plan

  private def doCreatePlans(
    indexMatch: RelationshipIndexMatch,
    hints: Set[Hint],
    argumentIds: Set[String],
    context: LogicalPlanningContext
  ): Set[LogicalPlan] = {
    indexMatch.propertyPredicates.flatMap { indexPredicate =>
      indexPredicate.predicate match {
        case predicate @ (_: Contains | _: EndsWith) =>
          val (valueExpr, stringSearchMode) = predicate match {
            case contains: Contains =>
              (contains.rhs, ContainsSearchMode)
            case endsWith: EndsWith =>
              (endsWith.rhs, EndsWithSearchMode)
          }
          val singlePredicateSet = indexMatch.predicateSet(Seq(indexPredicate), exactPredicatesCanGetValue = false)

          def provideRelationshipLeafPlan(
            patternForLeafPlan: PatternRelationship,
            originalPattern: PatternRelationship,
            hiddenSelections: Seq[Expression]
          ): LogicalPlan = {

            val hint = singlePredicateSet
              .fulfilledHints(hints, indexMatch.indexDescriptor.indexType, planIsScan = true)
              .headOption

            context.logicalPlanProducer.planRelationshipIndexStringSearchScan(
              idName = indexMatch.variableName,
              relationshipType = indexMatch.relationshipTypeToken,
              patternForLeafPlan = patternForLeafPlan,
              originalPattern = originalPattern,
              properties = singlePredicateSet.indexedProperties(context),
              stringSearchMode = stringSearchMode,
              solvedPredicates = singlePredicateSet.allSolvedPredicates,
              solvedHint = hint,
              hiddenSelections = hiddenSelections,
              valueExpr = valueExpr,
              argumentIds = argumentIds,
              providedOrder = indexMatch.providedOrder,
              indexOrder = indexMatch.indexOrder,
              context = context,
              indexType = indexMatch.indexDescriptor.indexType
            )
          }

          Some(planHiddenSelectionAndRelationshipLeafPlan(
            argumentIds,
            indexMatch.patternRelationship,
            context,
            provideRelationshipLeafPlan
          ))

        case _ =>
          None
      }

    }.toSet
  }
}
