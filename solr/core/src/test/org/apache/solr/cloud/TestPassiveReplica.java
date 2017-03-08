/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.lucene.util.LuceneTestCase.Slow;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.CollectionStatePredicate;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.util.TestInjection;
import org.apache.solr.util.TimeOut;
import org.apache.zookeeper.KeeperException;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotsearch.randomizedtesting.annotations.Repeat;

@Slow
public class TestPassiveReplica extends SolrCloudTestCase {
  
  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  
  // TODO: test no ulog in passive replicas
  // TODO: Make sure that FORCELEADER can't be used with Passive
  // TODO: Backup/Snapshot should not work on passive replicas 
  // TODO: ADDSHARD operation
  
  private String collectionName = null;
  private final static int REPLICATION_TIMEOUT_SECS = 10;
  
  private String suggestedCollectionName() {
    return (getTestClass().getSimpleName().replace("Test", "") + "_" + getTestName().split(" ")[0]).replaceAll("(.)(\\p{Upper})", "$1_$2").toLowerCase();
  }

  @BeforeClass
  public static void setupCluster() throws Exception {
    TestInjection.waitForReplicasInSync = null; // We'll be explicit about this in this test
    configureCluster(2) // 2 + random().nextInt(3) 
        .addConfig("conf", configset("cloud-minimal"))
        .configure();
    CollectionAdminRequest.ClusterProp clusterPropRequest = CollectionAdminRequest.setClusterProperty(ZkStateReader.LEGACY_CLOUD, "false");
    CollectionAdminResponse response = clusterPropRequest.process(cluster.getSolrClient());
    assertEquals(0, response.getStatus());
  }
  
  @Override
  public void setUp() throws Exception {
    super.setUp();
    collectionName = suggestedCollectionName();
    expectThrows(SolrException.class, () -> getCollectionState(collectionName));
  }

  @Override
  public void tearDown() throws Exception {
    for (JettySolrRunner jetty:cluster.getJettySolrRunners()) {
      if (!jetty.isRunning()) {
        LOG.warn("Jetty {} not running, probably some bad test. Starting it", jetty.getLocalPort());
        ChaosMonkey.start(jetty);
      }
    }
    if (cluster.getSolrClient().getZkStateReader().getClusterState().getCollectionOrNull(collectionName) != null) {
      LOG.info("tearDown deleting collection");
      CollectionAdminRequest.deleteCollection(collectionName).process(cluster.getSolrClient());
      waitForDeletion(collectionName);
    }
    super.tearDown();
  }
  
  // Just to compare test time, nocommit
  @Ignore
  public void testCreateDelete2() throws Exception {
    try {
      CollectionAdminRequest.createCollection(collectionName, "conf", 1, 8, 0, 0).process(cluster.getSolrClient());
      DocCollection docCollection = getCollectionState(collectionName);
      assertNotNull(docCollection);
//      assertEquals("Expecting 4 relpicas per shard",
//          8, docCollection.getReplicas().size());
//      assertEquals("Expecting 6 passive replicas, 3 per shard",
//          6, docCollection.getReplicas(EnumSet.of(Replica.Type.PASSIVE)).size());
//      assertEquals("Expecting 2 writer replicas, one per shard",
//          2, docCollection.getReplicas(EnumSet.of(Replica.Type.WRITER)).size());
//      for (Slice s:docCollection.getSlices()) {
//        // read-only replicas can never become leaders
//        assertFalse(s.getLeader().isReadOnly());
//      }
    } finally {
      zkClient().printLayoutToStdOut();
    }
  }
  
  @Repeat(iterations=2) // 2 times to make sure cleanup is complete and we can create the same collection
  public void testCreateDelete() throws Exception {
    try {
      CollectionAdminRequest.createCollection(collectionName, "conf", 2, 1, 0, 3).process(cluster.getSolrClient());
      DocCollection docCollection = getCollectionState(collectionName);
      assertNotNull(docCollection);
      assertEquals("Expecting 4 relpicas per shard",
          8, docCollection.getReplicas().size());
      assertEquals("Expecting 6 passive replicas, 3 per shard",
          6, docCollection.getReplicas(EnumSet.of(Replica.Type.PASSIVE)).size());
      assertEquals("Expecting 2 writer replicas, one per shard",
          2, docCollection.getReplicas(EnumSet.of(Replica.Type.REALTIME)).size());
      for (Slice s:docCollection.getSlices()) {
        // read-only replicas can never become leaders
        assertFalse(s.getLeader().getType() == Replica.Type.PASSIVE);
      }
      
      // TODO assert collection shards elect queue and validate there are no passive replicas
    } finally {
      zkClient().printLayoutToStdOut();
    }
  }
  
  public void testAddDocs() throws Exception {
    int numReadOnlyReplicas = 1 + random().nextInt(3);
    CollectionAdminRequest.createCollection(collectionName, "conf", 1, 1, 0, numReadOnlyReplicas).process(cluster.getSolrClient());
    waitForState("Expected collection to be created with 1 shard and " + (numReadOnlyReplicas + 1) + " replicas", collectionName, clusterShape(1, numReadOnlyReplicas + 1));
    DocCollection docCollection = assertNumberOfReplicas(1, 0, numReadOnlyReplicas, false, true);
    assertEquals(1, docCollection.getSlices().size());
    
    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "1", "foo", "bar"));
    cluster.getSolrClient().commit(collectionName);
    
    Slice s = docCollection.getSlices().iterator().next();
    try (HttpSolrClient leaderClient = getHttpSolrClient(s.getLeader().getCoreUrl())) {
      leaderClient.commit(); // TODO: this shouldn't be necessary here
      assertEquals(1, leaderClient.query(new SolrQuery("*:*")).getResults().getNumFound());
    }
    
    TimeOut t = new TimeOut(REPLICATION_TIMEOUT_SECS, TimeUnit.SECONDS);
    for (Replica r:s.getReplicas(EnumSet.of(Replica.Type.PASSIVE))) {
      //TODO: assert replication < REPLICATION_TIMEOUT_SECS
      try (HttpSolrClient readOnlyReplicaClient = getHttpSolrClient(r.getCoreUrl())) {
        while (true) {
          try {
            assertEquals("Replica " + r.getName() + " not up to date after 10 seconds",
                1, readOnlyReplicaClient.query(new SolrQuery("*:*")).getResults().getNumFound());
            break;
          } catch (AssertionError e) {
            if (t.hasTimedOut()) {
              throw e;
            } else {
              Thread.sleep(100);
            }
          }
        }
        SolrQuery req = new SolrQuery(
            "qt", "/admin/plugins",
            "stats", "true");
        QueryResponse statsResponse = readOnlyReplicaClient.query(req);
        assertEquals("Replicas shouldn't process the add document request: " + statsResponse, 
            0L, ((NamedList<Object>)statsResponse.getResponse()).findRecursive("plugins", "UPDATE", "updateHandler", "stats", "adds"));
      }
    }
  }
  
  public void testAddRemovePassiveReplica() throws Exception {
    CollectionAdminRequest.createCollection(collectionName, "conf", 2, 1, 0, 0)
      .setMaxShardsPerNode(100)
      .process(cluster.getSolrClient());
    cluster.getSolrClient().getZkStateReader().registerCore(collectionName); //TODO: Is this needed? 
    waitForState("Expected collection to be created with 2 shards and 1 replica each", collectionName, clusterShape(2, 1));
    DocCollection docCollection = assertNumberOfReplicas(2, 0, 0, false, true);
    assertEquals(2, docCollection.getSlices().size());
    
    CollectionAdminRequest.addReplicaToShard(collectionName, "shard1", Replica.Type.PASSIVE).process(cluster.getSolrClient());
    docCollection = assertNumberOfReplicas(2, 0, 1, true, false);
    CollectionAdminRequest.addReplicaToShard(collectionName, "shard2", Replica.Type.PASSIVE).process(cluster.getSolrClient());    
    docCollection = assertNumberOfReplicas(2, 0, 2, true, false);
    
    waitForState("Expecting collection to have 2 shards and 2 replica each", collectionName, clusterShape(2, 2));
    
    //Delete passive replica from shard1
    CollectionAdminRequest.deleteReplica(
        collectionName, 
        "shard1", 
        docCollection.getSlice("shard1").getReplicas(EnumSet.of(Replica.Type.PASSIVE)).get(0).getName())
    .process(cluster.getSolrClient());
    assertNumberOfReplicas(2, 0, 1, true, true);
  }
  
  public void testRemoveAllWriterReplicas() throws Exception {
    doTestNoLeader(true);
  }
  
  public void testKillLeader() throws Exception {
    doTestNoLeader(false);
  }
  
  public void testPassiveReplicaStates() {
    // Validate that passive replicas go through the correct states when starting, stopping, reconnecting
  }
  
  public void testPassiveReplicaCantConnectToZooKeeper() {
    
  }
  
  public void testRealTimeGet() {
    // should be redirected to writers
  }
  
  /*
   * validate that replication still happens on a new leader
   */
  private void doTestNoLeader(boolean removeReplica) throws Exception {
    CollectionAdminRequest.createCollection(collectionName, "conf", 1, 1, 0, 1)
      .setMaxShardsPerNode(100)
      .process(cluster.getSolrClient());
    cluster.getSolrClient().getZkStateReader().registerCore(collectionName); //TODO: Is this needed? 
    waitForState("Expected collection to be created with 1 shard and 2 replicas", collectionName, clusterShape(1, 2));
    DocCollection docCollection = assertNumberOfReplicas(1, 0, 1, false, true);
    
    // Add a document and commit
    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "1", "foo", "bar"));
    cluster.getSolrClient().commit(collectionName);
    Slice s = docCollection.getSlices().iterator().next();
    try (HttpSolrClient leaderClient = getHttpSolrClient(s.getLeader().getCoreUrl())) {
      assertEquals(1, leaderClient.query(new SolrQuery("*:*")).getResults().getNumFound());
    }
    
    waitForNumDocsInAllReplicas(1, docCollection.getReplicas(EnumSet.of(Replica.Type.PASSIVE)));
    
    // Delete leader replica from shard1
    ignoreException("No registered leader was found"); //These are expected
    JettySolrRunner leaderJetty = null;
    if (removeReplica) {
      CollectionAdminRequest.deleteReplica(
          collectionName, 
          "shard1", 
          s.getLeader().getName())
      .process(cluster.getSolrClient());
    } else {
      leaderJetty = cluster.getReplicaJetty(s.getLeader());
      ChaosMonkey.kill(leaderJetty);
      waitForState("Leader replica not removed", collectionName, clusterShape(1, 1));
      // Wait for cluster state to be updated
      waitForState("Replica state not updated in cluster state", 
          collectionName, clusterStateReflectsActiveAndDownReplicas());
    }
    docCollection = assertNumberOfReplicas(0, 0, 1, true, true);
    
    // Check that there is no leader for the shard
    Replica leader = docCollection.getSlice("shard1").getLeader();
    assertTrue(leader == null || !leader.isActive(cluster.getSolrClient().getZkStateReader().getClusterState().getLiveNodes()));
    
    // Passive replica on the other hand should be active
    Replica passiveReplica = docCollection.getSlice("shard1").getReplicas(EnumSet.of(Replica.Type.PASSIVE)).get(0);
    assertTrue(passiveReplica.isActive(cluster.getSolrClient().getZkStateReader().getClusterState().getLiveNodes()));

    // add document, this should fail since there is no leader. Passive replica should not accept the update
    expectThrows(SolrException.class, () -> 
      cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "2", "foo", "zoo"))
    );
    
    // Also fails if I send the update to the passive replica explicitly
    try (HttpSolrClient passiveReplicaClient = getHttpSolrClient(docCollection.getReplicas(EnumSet.of(Replica.Type.PASSIVE)).get(0).getCoreUrl())) {
      expectThrows(SolrException.class, () -> 
        cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "2", "foo", "zoo"))
      );
    }
    
    // Queries should still work
    waitForNumDocsInAllReplicas(1, docCollection.getReplicas(EnumSet.of(Replica.Type.PASSIVE)));
    // Add writer back. Since there is no writer now, new writer will have no docs. There will be data loss, since the it will become the leader
    // and passive replicas will replicate from it. Maybe we want to change this. Replicate from passive replicas is not a good idea, since they
    // are by definition out of date.
    if (removeReplica) {
      CollectionAdminRequest.addReplicaToShard(collectionName, "shard1", Replica.Type.REALTIME).process(cluster.getSolrClient());
    } else {
      ChaosMonkey.start(leaderJetty);
    }
    waitForState("Expected collection to be 1x2", collectionName, clusterShape(1, 2));
    unIgnoreException("No registered leader was found"); // Should have a leader from now on

    // Validate that the new writer is the leader now
    cluster.getSolrClient().getZkStateReader().forceUpdateCollection(collectionName);
    docCollection = getCollectionState(collectionName);
    leader = docCollection.getSlice("shard1").getLeader();
    assertTrue(leader != null && leader.isActive(cluster.getSolrClient().getZkStateReader().getClusterState().getLiveNodes()));

    //nocommit: If jetty is restarted, the replication is not forced, and replica doesn't replicate from leader until new docs are added. Is this the correct behavior? Why should these two cases be different?
    if (removeReplica) {
      // Passive replicas will replicate the empty index if a new replica was added and becomes leader
      waitForNumDocsInAllReplicas(0, docCollection.getReplicas(EnumSet.of(Replica.Type.PASSIVE)));
    }
    
    // add docs agin
    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "2", "foo", "zoo"));
    s = docCollection.getSlices().iterator().next();
    try (HttpSolrClient leaderClient = getHttpSolrClient(s.getLeader().getCoreUrl())) {
      leaderClient.commit();
      assertEquals(1, leaderClient.query(new SolrQuery("*:*")).getResults().getNumFound());
    }
    waitForNumDocsInAllReplicas(1, docCollection.getReplicas(EnumSet.of(Replica.Type.PASSIVE)), "id:2");
    waitForNumDocsInAllReplicas(1, docCollection.getReplicas(EnumSet.of(Replica.Type.PASSIVE)));
  }
  
  public void testKillReadOnlyReplica() throws Exception {
    CollectionAdminRequest.createCollection(collectionName, "conf", 1, 1, 0, 1)
      .setMaxShardsPerNode(100)
      .process(cluster.getSolrClient());
    cluster.getSolrClient().getZkStateReader().registerCore(collectionName); //TODO: Is this needed? 
    waitForState("Expected collection to be created with 1 shard and 2 replicas", collectionName, clusterShape(1, 2));
    DocCollection docCollection = assertNumberOfReplicas(1, 0, 1, false, true);
    assertEquals(1, docCollection.getSlices().size());
    
    waitForNumDocsInAllActiveReplicas(0);
    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "1", "foo", "bar"));
    cluster.getSolrClient().commit(collectionName);
    waitForNumDocsInAllActiveReplicas(1);
    
    JettySolrRunner passiveReplicaJetty = cluster.getReplicaJetty(docCollection.getSlice("shard1").getReplicas(EnumSet.of(Replica.Type.PASSIVE)).get(0));
    ChaosMonkey.kill(passiveReplicaJetty);
    waitForState("Replica not removed", collectionName, activeReplicaCount(1, 0, 0));
    // Also wait for the replica to be placed in state="down"
    waitForState("Didn't update state", collectionName, clusterStateReflectsActiveAndDownReplicas());
    
    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "2", "foo", "bar"));
    cluster.getSolrClient().commit(collectionName);
    waitForNumDocsInAllActiveReplicas(2);
    
    ChaosMonkey.start(passiveReplicaJetty);
    waitForState("Replica not added", collectionName, activeReplicaCount(1, 0, 1));
    waitForNumDocsInAllActiveReplicas(2);
  }
  
  public void testAddDocsToPassive() {
    
  }
  
  public void testSearchWhileReplicationHappens() {
      
  }
  
  private void waitForNumDocsInAllActiveReplicas(int numDocs) throws IOException, SolrServerException, InterruptedException {
    DocCollection docCollection = getCollectionState(collectionName);
    waitForNumDocsInAllReplicas(numDocs, docCollection.getReplicas().stream().filter(r -> r.getState() == Replica.State.ACTIVE).collect(Collectors.toList()));
  }
    
  private void waitForNumDocsInAllReplicas(int numDocs, Collection<Replica> replicas) throws IOException, SolrServerException, InterruptedException {
    waitForNumDocsInAllReplicas(numDocs, replicas, "*:*");
  }
  
  private void waitForNumDocsInAllReplicas(int numDocs, Collection<Replica> replicas, String query) throws IOException, SolrServerException, InterruptedException {
    TimeOut t = new TimeOut(REPLICATION_TIMEOUT_SECS, TimeUnit.SECONDS);
    for (Replica r:replicas) {
      try (HttpSolrClient replicaClient = getHttpSolrClient(r.getCoreUrl())) {
        while (true) {
          try {
            assertEquals("Replica " + r.getName() + " not up to date after " + REPLICATION_TIMEOUT_SECS + " seconds",
                numDocs, replicaClient.query(new SolrQuery(query)).getResults().getNumFound());
            break;
          } catch (AssertionError e) {
            if (t.hasTimedOut()) {
              throw e;
            } else {
              Thread.sleep(100);
            }
          }
        }
      }
    }
  }
  
  private void waitForDeletion(String collection) throws InterruptedException, KeeperException {
    TimeOut t = new TimeOut(10, TimeUnit.SECONDS);
    while (cluster.getSolrClient().getZkStateReader().getClusterState().hasCollection(collection)) {
      try {
        Thread.sleep(100);
        if (t.hasTimedOut()) {
          fail("Timed out waiting for collection " + collection + " to be deleted.");
        }
        cluster.getSolrClient().getZkStateReader().forceUpdateCollection(collection);
      } catch(SolrException e) {
        return;
      }
      
    }
  }
  
  private DocCollection assertNumberOfReplicas(int numWriter, int numActive, int numPassive, boolean updateCollection, boolean activeOnly) throws KeeperException, InterruptedException {
    if (updateCollection) {
      cluster.getSolrClient().getZkStateReader().forceUpdateCollection(collectionName);
    }
    DocCollection docCollection = getCollectionState(collectionName);
    assertNotNull(docCollection);
    assertEquals("Unexpected number of writer replicas: " + docCollection, numWriter, 
        docCollection.getReplicas(EnumSet.of(Replica.Type.REALTIME)).stream().filter(r->!activeOnly || r.getState() == Replica.State.ACTIVE).count());
    assertEquals("Unexpected number of passive replicas: " + docCollection, numPassive, 
        docCollection.getReplicas(EnumSet.of(Replica.Type.PASSIVE)).stream().filter(r->!activeOnly || r.getState() == Replica.State.ACTIVE).count());
    assertEquals("Unexpected number of active replicas: " + docCollection, numActive, 
        docCollection.getReplicas(EnumSet.of(Replica.Type.APPEND)).stream().filter(r->!activeOnly || r.getState() == Replica.State.ACTIVE).count());
    return docCollection;
  }
  
  /*
   * passes only if all replicas are active or down, and the "liveNodes" reflect the same status
   */
  private CollectionStatePredicate clusterStateReflectsActiveAndDownReplicas() {
    return (liveNodes, collectionState) -> {
      for (Replica r:collectionState.getReplicas()) {
        if (r.getState() != Replica.State.DOWN && r.getState() != Replica.State.ACTIVE) {
          return false;
        }
        if (r.getState() == Replica.State.DOWN && liveNodes.contains(r.getNodeName())) {
          return false;
        }
        if (r.getState() == Replica.State.ACTIVE && !liveNodes.contains(r.getNodeName())) {
          return false;
        }
      }
      return true;
    };
  }
  
  
  private CollectionStatePredicate activeReplicaCount(int numWriter, int numActive, int numPassive) {
    return (liveNodes, collectionState) -> {
      int writersFound = 0, activesFound = 0, passivesFound = 0;
      if (collectionState == null)
        return false;
      for (Slice slice : collectionState) {
        for (Replica replica : slice) {
          if (replica.isActive(liveNodes))
            switch (replica.getType()) {
              case APPEND:
                activesFound++;
                break;
              case PASSIVE:
                passivesFound++;
                break;
              case REALTIME:
                writersFound++;
                break;
              default:
                throw new AssertionError("Unexpected replica type");
            }
        }
      }
      return numWriter == writersFound && numActive == activesFound && numPassive == passivesFound;
    };
  }
  
  private void addDocs(int numDocs) throws SolrServerException, IOException {
    for (int i = 0; i < numDocs; i++) {
      cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", String.valueOf(i), "fieldName_s", String.valueOf(i)));
    }
    cluster.getSolrClient().commit(collectionName);
  }
}
