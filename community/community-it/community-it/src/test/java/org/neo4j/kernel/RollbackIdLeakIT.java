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
package org.neo4j.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.kernel.impl.MyRelTypes.TEST;
import static org.neo4j.test.Race.throwing;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.assertj.core.data.Percentage;
import org.eclipse.collections.api.set.primitive.LongSet;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.factory.primitive.LongSets;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.junit.jupiter.api.Test;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.id.IdController;
import org.neo4j.io.fs.EphemeralFileSystemAbstraction;
import org.neo4j.io.fs.UncloseableDelegatingFileSystemAbstraction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.test.Barrier;
import org.neo4j.test.Race;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.testdirectory.EphemeralTestDirectoryExtension;
import org.neo4j.test.utils.TestDirectory;

@EphemeralTestDirectoryExtension
class RollbackIdLeakIT {
    @Inject
    private TestDirectory directory;

    @Inject
    private EphemeralFileSystemAbstraction fs;

    @Test
    void shouldNotLeakHighIdsOnRollbackAfterCleanRestart() throws IOException {
        shouldNotLeakHighIdsOnRollback(this::clean);
    }

    @Test
    void shouldNotLeakHighIdsOnRollbackAfterCrashAndRecovery() throws IOException {
        shouldNotLeakHighIdsOnRollback(this::nonClean);
    }

    @Test
    void shouldNotLeakHighIdsOnRollbackAfterIdMaintenance() throws IOException {
        shouldNotLeakHighIdsOnRollback(this::idMaintenance);
    }

    private static void shouldNotLeakHighIdsOnRollback(Supplier<DbRestarter> restarterSupplier) throws IOException {
        // given some (lower) node/relationship ids rolled back and some (higher) node/relationship ids committed
        MutableLongSet rolledBackNodeIds = new LongHashSet();
        MutableLongSet rolledBackRelationshipIds = new LongHashSet();
        MutableLongSet committedNodeIds = new LongHashSet();
        MutableLongSet committedRelationshipIds = new LongHashSet();
        try (DbRestarter restarter = restarterSupplier.get()) {
            {
                GraphDatabaseService db = restarter.start();
                Barrier.Control flowControl = new Barrier.Control();
                Race race = new Race();
                race.addContestant(
                        () -> {
                            try (Transaction tx = db.beginTx()) {
                                Node node = tx.createNode();
                                Relationship relationship = node.createRelationshipTo(node, TEST);
                                rolledBackNodeIds.add(node.getId());
                                rolledBackRelationshipIds.add(relationship.getId());
                                flowControl.reached();
                                // DO NOT call tx.commit()
                            }
                        },
                        1);
                race.addContestant(
                        throwing(() -> {
                            flowControl.await();
                            try (Transaction tx = db.beginTx()) {
                                Node node = tx.createNode();
                                Relationship relationship = node.createRelationshipTo(node, TEST);
                                committedNodeIds.add(node.getId());
                                committedRelationshipIds.add(relationship.getId());
                                tx.commit(); // this one we commit
                            }
                            flowControl.release();
                        }),
                        1);
                race.goUnchecked();

                // when deleting the committed nodes/relationships and restarting
                try (Transaction tx = db.beginTx()) {
                    committedNodeIds.forEach(nodeId -> tx.getNodeById(nodeId).delete());
                    committedRelationshipIds.forEach(relationshipId ->
                            tx.getRelationshipById(relationshipId).delete());
                    tx.commit();
                }
            }

            // then after a restart both the ids from rolled back and committed transactions should be reused
            MutableLongSet nodeIds = new LongHashSet();
            nodeIds.addAll(rolledBackNodeIds);
            nodeIds.addAll(committedNodeIds);
            MutableLongSet relationshipIds = new LongHashSet();
            relationshipIds.addAll(rolledBackRelationshipIds);
            relationshipIds.addAll(committedRelationshipIds);
            assertAllocateIds(restarter.restart(), nodeIds, relationshipIds);
        }
    }

    @Test
    void shouldNotLeakHighIdsOnCreateDeleteInSameTxAfterCleanRestart() throws IOException {
        shouldNotLeakHighIdsOnCreateDeleteInSameTx(this::clean);
    }

    @Test
    void shouldNotLeakHighIdsOnCreateDeleteInSameTxAfterCrashAndRecovery() throws IOException {
        shouldNotLeakHighIdsOnCreateDeleteInSameTx(this::nonClean);
    }

    @Test
    void shouldNotLeakHighIdsOnCreateDeleteInSameTxAfterIdMaintenance() throws IOException {
        shouldNotLeakHighIdsOnCreateDeleteInSameTx(this::idMaintenance);
    }

    private static void shouldNotLeakHighIdsOnCreateDeleteInSameTx(Supplier<DbRestarter> restarterSupplier)
            throws IOException {
        // given
        MutableLongSet nodeIds = new LongHashSet();
        MutableLongSet relationshipIds = new LongHashSet();
        try (DbRestarter restarter = restarterSupplier.get()) {
            GraphDatabaseService db = restarter.start();
            Node node2;
            Relationship relationship2;
            try (Transaction tx = db.beginTx()) {
                Node node1 = tx.createNode();
                Relationship relationship1 = node1.createRelationshipTo(node1, TEST);

                node2 = tx.createNode();
                relationship2 = node2.createRelationshipTo(node2, TEST);
                nodeIds.add(node2.getId());
                relationshipIds.add(relationship2.getId());

                // Delete first node/relationship, but keep second ones
                node1.delete();
                nodeIds.add(node1.getId());
                relationship1.delete();
                relationshipIds.add(relationship1.getId());

                tx.commit();
            }

            try (Transaction tx = db.beginTx()) {
                tx.getNodeById(node2.getId()).delete();
                tx.getRelationshipById(relationship2.getId()).delete();
                tx.commit();
            }

            assertAllocateIds(restarter.restart(), nodeIds, relationshipIds);
        }
    }

    @Test
    void shouldReuseRolledBackIds() throws IOException {
        try (var dbManager = clean()) {
            // given
            var db = dbManager.start();
            var race = new Race();
            var numTransactions = new AtomicInteger();
            var allocatedIds = LongSets.mutable.empty().asSynchronized();
            var threads = 4;
            var txSize = 5;
            var rounds = 1_000;
            race.addContestants(
                    threads,
                    () -> {
                        // Do some creations and randomly decide to commit or rollback
                        try (var tx = db.beginTx()) {
                            for (var i = 0; i < txSize; i++) {
                                allocatedIds.add(tx.createNode().getId());
                            }
                            if (numTransactions.incrementAndGet() % 2 == 0) {
                                tx.commit();
                            }
                        }
                        ((GraphDatabaseAPI) db)
                                .getDependencyResolver()
                                .resolveDependency(IdController.class)
                                .maintenance();
                    },
                    rounds);

            // when
            race.goUnchecked();

            // then
            var expectedMaxAllocatedIds = threads * txSize * rounds / 2;
            assertThat(allocatedIds.size()).isCloseTo(expectedMaxAllocatedIds, Percentage.withPercentage(10));
        }
    }

    @Test
    void shouldReuseRolledBackIdsMultipleTimes() throws IOException {
        try (var dbManager = clean()) {
            // given
            var db = dbManager.start();
            LongSet referenceIds = null;
            for (int r = 0; r < 10; r++) {
                var ids = LongSets.mutable.empty();
                try (var tx = db.beginTx()) {
                    for (var i = 0; i < 10; i++) {
                        var node = tx.createNode();
                        ids.add(node.getId());
                    }
                }
                if (r == 0) {
                    referenceIds = ids;
                } else {
                    assertThat(ids).isEqualTo(referenceIds);
                }
                ((GraphDatabaseAPI) db)
                        .getDependencyResolver()
                        .resolveDependency(IdController.class)
                        .maintenance();
            }
        }
    }

    private static void assertAllocateIds(
            GraphDatabaseService db, MutableLongSet nodeIds, MutableLongSet relationshipIds) {
        try (Transaction tx = db.beginTx()) {
            int nodes = nodeIds.size();
            int relationships = relationshipIds.size();
            for (int i = 0; i < nodes; i++) {
                Node node = tx.createNode();
                assertTrue(nodeIds.remove(node.getId()));
            }
            for (int i = 0; i < relationships; i++) {
                Relationship relationship = tx.createNode().createRelationshipTo(tx.createNode(), TEST);
                assertTrue(relationshipIds.remove(relationship.getId()));
            }
            tx.commit();
        }
        assertTrue(nodeIds.isEmpty());
        assertTrue(relationshipIds.isEmpty());
    }

    private DbRestarter clean() {
        return new DbRestarter() {
            private DatabaseManagementService dbms;

            @Override
            public GraphDatabaseService start() {
                dbms = new TestDatabaseManagementServiceBuilder(directory.homePath())
                        .setFileSystem(new UncloseableDelegatingFileSystemAbstraction(fs))
                        .build();
                return dbms.database(DEFAULT_DATABASE_NAME);
            }

            @Override
            public GraphDatabaseService restart() {
                close();
                return start();
            }

            @Override
            public void close() {
                dbms.shutdown();
            }
        };
    }

    private DbRestarter nonClean() {
        return new DbRestarter() {
            private DatabaseManagementService dbms;
            private EphemeralFileSystemAbstraction fsSnapshot;

            @Override
            public GraphDatabaseService start() {
                return start(fs);
            }

            @Override
            public GraphDatabaseService restart() {
                fsSnapshot = fs.snapshot();
                dbms.shutdown();
                return start(fsSnapshot);
            }

            @Override
            public void close() throws IOException {
                dbms.shutdown();
                fsSnapshot.close();
            }

            private GraphDatabaseService start(EphemeralFileSystemAbstraction fs) {
                dbms = new TestDatabaseManagementServiceBuilder(directory.homePath())
                        .setFileSystem(fs)
                        .build();
                return dbms.database(DEFAULT_DATABASE_NAME);
            }
        };
    }

    private DbRestarter idMaintenance() {
        return new DbRestarter() {
            private DatabaseManagementService dbms;

            @Override
            public GraphDatabaseService start() {
                dbms = new TestDatabaseManagementServiceBuilder(directory.homePath())
                        .setFileSystem(new UncloseableDelegatingFileSystemAbstraction(fs))
                        .build();
                return dbms.database(DEFAULT_DATABASE_NAME);
            }

            @Override
            public GraphDatabaseService restart() {
                var db = dbms.database(DEFAULT_DATABASE_NAME);
                ((GraphDatabaseAPI) db)
                        .getDependencyResolver()
                        .resolveDependency(IdController.class)
                        .maintenance();
                return db;
            }

            @Override
            public void close() {
                dbms.shutdown();
            }
        };
    }

    private interface DbRestarter extends Closeable {
        GraphDatabaseService start();

        GraphDatabaseService restart() throws IOException;
    }
}
