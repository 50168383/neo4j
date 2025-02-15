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
package org.neo4j.bolt.protocol.v43.fsm;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.neo4j.bolt.protocol.common.fsm.State;
import org.neo4j.bolt.protocol.common.fsm.StateMachineContext;
import org.neo4j.bolt.protocol.common.message.request.RequestMessage;
import org.neo4j.bolt.protocol.common.routing.RoutingTableGetter;
import org.neo4j.bolt.protocol.v43.message.request.RouteMessage;
import org.neo4j.memory.HeapEstimator;
import org.neo4j.values.virtual.MapValue;

/**
 * Extends the behaviour of a given State by adding the capacity of handle the {@link RouteMessage}
 */
public class ReadyState extends org.neo4j.bolt.protocol.v40.fsm.ReadyState {
    public static final long SHALLOW_SIZE = HeapEstimator.shallowSizeOfInstance(ReadyState.class);
    private static final String ROUTING_TABLE_KEY = "rt";

    private final RoutingTableGetter routingTableGetter;

    public ReadyState(RoutingTableGetter routingTableGetter) {
        this.routingTableGetter = routingTableGetter;
    }

    @Override
    public State processUnsafe(RequestMessage message, StateMachineContext context) throws Exception {
        if (message instanceof RouteMessage routeMessage) {
            return this.processRouteMessage(routeMessage, context);
        }

        return super.processUnsafe(message, context);
    }

    protected State processRouteMessage(RouteMessage message, StateMachineContext context) throws Exception {
        var programId = UUID.randomUUID().toString();
        context.connectionState().setCurrentTransactionId(programId);

        try {
            routingTableGetter
                    .get(
                            programId,
                            context.connection().loginContext(),
                            context.transactionManager(),
                            message.getRequestContext(),
                            message.getBookmarks(),
                            message.getDatabaseName(),
                            context.connectionId())
                    .thenAccept(routingTable -> this.onRoutingTableReceived(context, message, routingTable))
                    .join();
        } catch (CompletionException ex) {
            var cause = ex.getCause();
            if (cause != null) {
                context.handleFailure(cause, false);
                return this.failedState;
            }

            throw ex;
        }

        return this;
    }

    protected void onRoutingTableReceived(StateMachineContext context, RouteMessage message, MapValue routingTable) {
        context.connectionState().onMetadata(ROUTING_TABLE_KEY, routingTable);
    }
}
