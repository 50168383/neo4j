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
package org.neo4j.bolt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.neo4j.bolt.testing.assertions.BoltConnectionAssertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.neo4j.bolt.protocol.v40.bookmark.BookmarkWithDatabaseId;
import org.neo4j.bolt.test.annotation.BoltTestExtension;
import org.neo4j.bolt.test.annotation.connection.initializer.Authenticated;
import org.neo4j.bolt.test.annotation.test.ProtocolTest;
import org.neo4j.bolt.test.annotation.wire.selector.ExcludeWire;
import org.neo4j.bolt.test.annotation.wire.selector.Version;
import org.neo4j.bolt.test.util.ServerUtil;
import org.neo4j.bolt.testing.client.TransportConnection;
import org.neo4j.bolt.testing.messages.BoltWire;
import org.neo4j.bolt.transport.Neo4jWithSocket;
import org.neo4j.bolt.transport.Neo4jWithSocketExtension;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.testdirectory.EphemeralTestDirectoryExtension;

/**
 * Ensures that Bolt correctly handles the {@code ROUTE} messages and returns well-formed results.
 */
@EphemeralTestDirectoryExtension
@Neo4jWithSocketExtension
@BoltTestExtension
@ExcludeWire(@Version(major = 4, minor = 2, range = 2))
public class RoutingTableIT {

    @Inject
    private Neo4jWithSocket server;

    private static void assertRoutingTableHasCorrectShape(Map<?, ?> routingTable) {
        assertAll(
                () -> {
                    Assertions.assertThat(routingTable.containsKey("ttl")).isTrue();
                    Assertions.assertThat(routingTable.get("ttl")).isInstanceOf(Long.class);
                },
                () -> {
                    Assertions.assertThat(routingTable.containsKey("servers")).isTrue();
                    Assertions.assertThat(routingTable.get("servers"))
                            .isInstanceOf(List.class)
                            .satisfies(s -> {
                                var servers = (List<?>) s;
                                for (var srv : servers) {
                                    Assertions.assertThat(srv).isInstanceOf(Map.class);
                                    var server = (Map<?, ?>) srv;
                                    assertAll(
                                            () -> {
                                                Assertions.assertThat(server.containsKey("role"))
                                                        .isTrue();
                                                Assertions.assertThat(server.get("role"))
                                                        .isIn("READ", "WRITE", "ROUTE");
                                            },
                                            () -> {
                                                Assertions.assertThat(server.containsKey("addresses"))
                                                        .isTrue();
                                                Assertions.assertThat(server.get("addresses"))
                                                        .isInstanceOf(List.class)
                                                        .satisfies(ad -> {
                                                            var addresses = (List<?>) ad;
                                                            for (var address : addresses) {
                                                                Assertions.assertThat(address)
                                                                        .isInstanceOf(String.class);
                                                            }
                                                        });
                                            });
                                }
                            });
                });
    }

    @ProtocolTest
    void shouldRespondToRouteMessage(BoltWire wire, @Authenticated TransportConnection connection) throws IOException {
        connection.send(wire.route());

        assertThat(connection).receivesSuccess(metadata -> Assertions.assertThat(metadata)
                .hasEntrySatisfying("rt", rt -> Assertions.assertThat(rt)
                        .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                        .satisfies(RoutingTableIT::assertRoutingTableHasCorrectShape)));
    }

    @ProtocolTest
    void shouldRespondToRouteMessageWithBookmark(BoltWire wire, @Authenticated TransportConnection connection)
            throws IOException {
        var lastClosedTransactionId = ServerUtil.getLastClosedTransactionId(this.server);
        var routeBookmark = new BookmarkWithDatabaseId(lastClosedTransactionId, ServerUtil.getDatabaseId(this.server));

        connection.send(wire.route(null, List.of(routeBookmark.toString()), null));

        assertThat(connection).receivesSuccess(metadata -> {
            Assertions.assertThat(metadata.containsKey("rt")).isTrue();
            Assertions.assertThat(metadata.get("rt"))
                    .isInstanceOf(Map.class)
                    .satisfies(rt -> assertRoutingTableHasCorrectShape((Map<?, ?>) rt));
        });
    }

    @ProtocolTest
    void shouldReturnFailureIfRoutingTableFailedToReturn(BoltWire wire, @Authenticated TransportConnection connection)
            throws IOException {
        connection.send(wire.route(null, null, "DOESNT_EXIST!"));
        assertThat(connection).receivesFailure();

        connection.send(wire.reset());
        assertThat(connection).receivesSuccess();

        connection.send(wire.route());
        assertThat(connection).receivesSuccess(metadata -> {
            Assertions.assertThat(metadata.containsKey("rt")).isTrue();
            Assertions.assertThat(metadata.get("rt"))
                    .isInstanceOf(Map.class)
                    .satisfies(rt -> assertRoutingTableHasCorrectShape((Map<?, ?>) rt));
        });
    }

    @ProtocolTest
    void shouldIgnoreRouteMessageWhenInFailedState(BoltWire wire, @Authenticated TransportConnection connection)
            throws IOException {
        connection.send(wire.run("✨✨✨ Magical Crash String ✨✨✨"));
        assertThat(connection).receivesFailure();

        connection.send(wire.route());
        assertThat(connection).receivesIgnored();
    }
}
