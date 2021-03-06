/**
 * Licensed to the zk1931 under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.  You may obtain a copy of the
 * License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.zk1931.jzab;

import com.google.protobuf.TextFormat;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import com.github.zk1931.jzab.proto.ZabMessage;
import com.github.zk1931.jzab.proto.ZabMessage.Message;
import com.github.zk1931.jzab.proto.ZabMessage.Message.MessageType;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;

/**
 * Follower.
 */
public class Follower extends Participant {

  private static final Logger LOG = LoggerFactory.getLogger(Follower.class);

  public Follower(ParticipantState participantState,
                  StateMachine stateMachine,
                  ZabConfig config) {
    super(participantState, stateMachine, config);
    MDC.put("state", "following");
  }

  /**
   * Gets a message from the queue.
   *
   * @param timeoutMs how to wait before raising a TimeoutException.
   * @return a message tuple contains the message and its source.
   * @throws TimeoutException in case of timeout.
   * @throws InterruptedException it's interrupted.
   */
  @Override
  protected MessageTuple getMessage(int timeoutMs)
      throws TimeoutException, InterruptedException {
    while (true) {
      MessageTuple tuple = messageQueue.poll(timeoutMs,
                                             TimeUnit.MILLISECONDS);
      if (tuple == null) {
        // Timeout.
        throw new TimeoutException("Timeout while waiting for the message.");
      } else if (tuple == MessageTuple.GO_BACK) {
        // Goes back to leader election.
        throw new BackToElectionException();
      } else if (tuple.getMessage().getType() == MessageType.ELECTION_INFO) {
        // If it's election message, replies it directly.
        this.election.reply(tuple);
      } else if (tuple.getMessage().getType() == MessageType.PROPOSED_EPOCH) {
        // Explicitly close the connection when gets PROPOSED_EPOCH message in
        // FOLLOWING state to help the peer selecting the right leader faster.
        LOG.debug("Got PROPOSED_EPOCH in FOLLOWING state. Close connection.");
        this.transport.clear(tuple.getServerId());
      } else if (tuple.getMessage().getType() == MessageType.DISCONNECTED) {
        // Got DISCONNECTED message enqueued by onDisconnected callback.
        Message msg = tuple.getMessage();
        String peerId = msg.getDisconnected().getServerId();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Got message {}.",
                    TextFormat.shortDebugString(msg));
        }
        // FOLLOWING state.
        if (this.electedLeader != null && peerId.equals(this.electedLeader)) {
          // Disconnection from elected leader, going back to leader election,
          // the clearance of transport will happen in exception handlers of
          // follow/join function.
          LOG.debug("Lost elected leader {}.", this.electedLeader);
          throw new BackToElectionException();
        } else {
          // Lost connection to someone you don't care, clear transport.
          LOG.debug("Lost peer {}.", peerId);
          this.transport.clear(peerId);
        }
      } else if (tuple.getMessage().getType() == MessageType.SHUT_DOWN) {
        LOG.debug("Got SHUT_DOWN, going to shut down Zab.");
        throw new LeftCluster("Shutdown Zab");
      } else {
        return tuple;
      }
    }
  }

  @Override
  protected void changePhase(Phase phase) throws IOException {
    if (phase == Phase.DISCOVERING) {
      MDC.put("phase", "discovering");
      if (stateChangeCallback != null) {
        stateChangeCallback.followerDiscovering(this.electedLeader);
      }
      if (failCallback != null) {
        failCallback.followerDiscovering();
      }
    } else if (phase == Phase.SYNCHRONIZING) {
      MDC.put("phase", "synchronizing");
      if (stateChangeCallback != null) {
        stateChangeCallback
        .followerSynchronizing(persistence.getProposedEpoch());
      }
      if (failCallback != null) {
        failCallback.followerSynchronizing();
      }
    } else if (phase == Phase.BROADCASTING) {
      MDC.put("phase", "broadcasting");
      this.isBroadcasting = true;
      if (stateChangeCallback != null) {
        stateChangeCallback
        .followerBroadcasting(persistence.getAckEpoch(),
                              getAllTxns(),
                              persistence.getLastSeenConfig());
      }
      if (failCallback != null) {
        failCallback.followerBroadcasting();
      }
    }
    this.currentPhase = phase;
  }

  /**
   * Starts from joining some one who is in cluster..
   *
   * @param peer the id of server who is in cluster.
   * @throws Exception in case something goes wrong.
   */
  @Override
  public void join(String peer) throws Exception {
    LOG.debug("Follower joins in.");
    try {
      LOG.debug("Query leader from {}", peer);
      Message query = MessageBuilder.buildQueryLeader();
      MessageTuple tuple = null;
      while (true) {
        // The joiner might gets started before the cluster gets started.
        // Instead of reporting failure immediately, the follower will retry
        // joining the cluster until joining the cluster successfully.
        try {
          sendMessage(peer, query);
          tuple = getExpectedMessage(MessageType.QUERY_LEADER_REPLY, peer);
          break;
        } catch (TimeoutException ex) {
          long retryInterval = 1;
          LOG.warn("Timeout while contacting leader, going to retry after {} "
              + "second.", retryInterval);
          // Waits for 1 second.
          Thread.sleep(retryInterval * 1000);
        }
      }
      this.electedLeader = tuple.getMessage().getReply().getLeader();
      LOG.debug("Got current leader {}", this.electedLeader);

      /* -- Synchronizing phase -- */
      while (true) {
        try {
          joinSynchronization();
          break;
        } catch (TimeoutException | BackToElectionException ex) {
          LOG.debug("Timeout({} ms) in synchronizing, retrying. Last zxid : {}",
                    getSyncTimeoutMs(),
                    persistence.getLog().getLatestZxid());
          transport.clear(electedLeader);
          clearMessageQueue();
        }
      }
      // See if it can be restored from the snapshot file.
      restoreFromSnapshot();
      // Delivers all transactions in log before entering broadcasting phase.
      deliverUndeliveredTxns();

      /* -- Broadcasting phase -- */
      // Initialize the vote for leader election.
      this.election.specifyLeader(this.electedLeader);
      changePhase(Phase.BROADCASTING);
      accepting();
    } catch (InterruptedException e) {
      LOG.debug("Participant is canceled by user.");
      throw e;
    } catch (TimeoutException e) {
      LOG.debug("Didn't hear message from {} for {} milliseconds. Going"
                + " back to leader election.",
                this.electedLeader,
                this.config.getTimeoutMs());
      if (persistence.getLastSeenConfig() == null) {
        throw new JoinFailure("Fails to join cluster.");
      }
    } catch (BackToElectionException e) {
      LOG.debug("Got GO_BACK message from queue, going back to electing.");
      if (persistence.getLastSeenConfig() == null) {
        throw new JoinFailure("Fails to join cluster.");
      }
    } catch (LeftCluster e) {
      LOG.debug("Exit running : {}", e.getMessage());
      throw e;
    } catch (Exception e) {
      LOG.error("Caught exception", e);
      throw e;
    } finally {
      if (this.electedLeader != null) {
        this.transport.clear(this.electedLeader);
      }
      // Clears the message queue.
      clearMessageQueue();
    }
  }

  public void follow(String leader) throws Exception {
    this.electedLeader = leader;
    try {
      /* -- Discovering phase -- */
      changePhase(Phase.DISCOVERING);
      sendProposedEpoch();
      waitForNewEpoch();

      /* -- Synchronizing phase -- */
      LOG.debug("Synchronizing...");
      changePhase(Phase.SYNCHRONIZING);
      // Starts synchronizing.
      long st = System.nanoTime();
      waitForSync(this.electedLeader);
      waitForNewLeaderMessage();
      waitForCommitMessage();
      long syncTime = System.nanoTime() - st;
      // Adjusts the sync timeout based on this synchronization time.
      adjustSyncTimeout((int)(syncTime / 1000000));

      // See if it can be restored from the snapshot file.
      restoreFromSnapshot();
      // Delivers all transactions in log before entering broadcasting phase.
      deliverUndeliveredTxns();

      /* -- Broadcasting phase -- */
      changePhase(Phase.BROADCASTING);
      LOG.info("FOLLOWING");
      accepting();
    } catch (InterruptedException e) {
      LOG.debug("Participant is canceled by user.");
      throw e;
    } catch (TimeoutException e) {
      LOG.debug("Didn't hear message from {} for {} milliseconds. Going"
                + " back to leader election.",
                this.electedLeader,
                this.config.getTimeoutMs());
    } catch (BackToElectionException e) {
      LOG.debug("Got GO_BACK message from queue, going back to electing.");
    } catch (Zab.SimulatedException e) {
      LOG.debug("Got SimulatedException, go back to leader election.");
    } catch (LeftCluster e) {
      LOG.debug("Exit running : {}", e.getMessage());
      throw e;
    } catch (Exception e) {
      LOG.error("Caught exception", e);
      throw e;
    } finally {
      this.transport.clear(this.electedLeader);
      // Clears the message queue.
      clearMessageQueue();
      if (this.currentPhase == Phase.SYNCHRONIZING) {
        incSyncTimeout();
        LOG.debug("Go back to recovery in synchronization phase, increase " +
            "sync timeout to {} milliseconds.", getSyncTimeoutMs());
      }
    }
  }

  /**
   * Sends CEPOCH message to its prospective leader.
   * @throws IOException in case of IO failure.
   */
  void sendProposedEpoch() throws IOException {
    Message message = MessageBuilder
                      .buildProposedEpoch(persistence.getProposedEpoch(),
                                          persistence.getAckEpoch(),
                                          persistence.getLastSeenConfig(),
                                          getSyncTimeoutMs());
    sendMessage(this.electedLeader, message);
  }

  /**
   * Waits until receives the NEWEPOCH message from leader.
   *
   * @throws InterruptedException if anything wrong happens.
   * @throws TimeoutException in case of timeout.
   * @throws IOException in case of IO failure.
   */
  void waitForNewEpoch()
      throws InterruptedException, TimeoutException, IOException {
    MessageTuple tuple = getExpectedMessage(MessageType.NEW_EPOCH,
                                            this.electedLeader);
    Message msg = tuple.getMessage();
    String source = tuple.getServerId();
    ZabMessage.NewEpoch epoch = msg.getNewEpoch();
    if (epoch.getNewEpoch() < persistence.getProposedEpoch()) {
      LOG.error("New epoch {} from {} is smaller than last received "
                + "proposed epoch {}",
                epoch.getNewEpoch(),
                source,
                persistence.getProposedEpoch());
      throw new RuntimeException("New epoch is smaller than current one.");
    }
    // Updates follower's last proposed epoch.
    persistence.setProposedEpoch(epoch.getNewEpoch());
    // Updates follower's sync timeout.
    setSyncTimeoutMs(epoch.getSyncTimeout());
    LOG.debug("Received the new epoch proposal {} from {}.",
              epoch.getNewEpoch(),
              source);
    Zxid zxid = persistence.getLog().getLatestZxid();
    // Sends ACK to leader.
    sendMessage(this.electedLeader,
                MessageBuilder.buildAckEpoch(persistence.getAckEpoch(),
                                             zxid));
  }

  /**
   * Waits for NEW_LEADER message and sends back ACK and update ACK epoch.
   *
   * @throws TimeoutException in case of timeout.
   * @throws InterruptedException in case of interrupt.
   * @throws IOException in case of IO failure.
   */
  void waitForNewLeaderMessage()
      throws TimeoutException, InterruptedException, IOException {
    LOG.debug("Waiting for New Leader message from {}.", this.electedLeader);
    MessageTuple tuple = getExpectedMessage(MessageType.NEW_LEADER,
                                            this.electedLeader);
    Message msg = tuple.getMessage();
    String source = tuple.getServerId();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Got message {} from {}.",
                TextFormat.shortDebugString(msg),
                source);
    }
    ZabMessage.NewLeader nl = msg.getNewLeader();
    long epoch = nl.getEpoch();
    Log log = persistence.getLog();
    // Sync Ack epoch to disk.
    log.sync();
    persistence.setAckEpoch(epoch);
    Message ack = MessageBuilder.buildAck(log.getLatestZxid());
    sendMessage(source, ack);
  }

  /**
   * Wait for a commit message from the leader.
   *
   * @throws TimeoutException in case of timeout.
   * @throws InterruptedException in case of interruption.
   * @throws IOException in case of IO failures.
   */
  void waitForCommitMessage()
      throws TimeoutException, InterruptedException, IOException {
    LOG.debug("Waiting for commit message from {}", this.electedLeader);
    MessageTuple tuple = getExpectedMessage(MessageType.COMMIT,
                                            this.electedLeader);
    Zxid zxid = MessageBuilder.fromProtoZxid(tuple.getMessage()
                                                  .getCommit()
                                                  .getZxid());
    Zxid lastZxid = persistence.getLog().getLatestZxid();
    // If the followers are appropriately synchronized, the Zxid of ACK should
    // match the last Zxid in followers' log.
    if (zxid.compareTo(lastZxid) != 0) {
      LOG.error("The ACK zxid {} doesn't match last zxid {} in log!",
                zxid,
                lastZxid);
      throw new RuntimeException("The ACK zxid doesn't match last zxid");
    }
  }

  /**
   * Entering broadcasting phase.
   *
   * @throws InterruptedException if it's interrupted.
   * @throws TimeoutException  in case of timeout.
   * @throws IOException in case of IOException.
   * @throws ExecutionException in case of exception from executors.
   */
  void accepting()
      throws TimeoutException, InterruptedException, IOException,
      ExecutionException {
    ClusterConfiguration clusterConfig = persistence.getLastSeenConfig();
    SyncProposalProcessor syncProcessor =
      new SyncProposalProcessor(persistence, transport, SYNC_MAX_BATCH_SIZE);
    CommitProcessor commitProcessor
      = new CommitProcessor(stateMachine, lastDeliveredZxid, serverId,
                            transport, null, clusterConfig, electedLeader,
                            semPendingReqs);
    SnapshotProcessor snapProcessor =
      new SnapshotProcessor(stateMachine, persistence);
    // The last time of HEARTBEAT message comes from leader.
    long lastHeartbeatTime = System.nanoTime();
    long ackEpoch = persistence.getAckEpoch();
    // Notifies the client current configuration.
    stateMachine.following(electedLeader,
                           new HashSet<String>(clusterConfig.getPeers()));
    // Starts thread to process request in request queue.
    SendRequestTask sendTask = new SendRequestTask(this.electedLeader);
    try {
      while (true) {
        MessageTuple tuple = getMessage();
        Message msg = tuple.getMessage();
        String source = tuple.getServerId();
        if (msg.getType() == MessageType.QUERY_LEADER) {
          LOG.debug("Got QUERY_LEADER from {}", source);
          Message reply = MessageBuilder.buildQueryReply(this.electedLeader);
          sendMessage(source, reply);
          continue;
        }
        // The follower only expect receiving message from leader and
        // itself(REQUEST).
        if (source.equals(this.electedLeader)) {
          lastHeartbeatTime = System.nanoTime();
        } else {
          // Checks if the leader is alive.
          long timeDiff = (System.nanoTime() - lastHeartbeatTime) / 1000000;
          if ((int)timeDiff >= this.config.getTimeoutMs()) {
            // HEARTBEAT timeout.
            LOG.warn("Detects there's a timeout in waiting"
                + "message from leader {}, goes back to leader electing",
                this.electedLeader);
            throw new TimeoutException("HEARTBEAT timeout!");
          }
          if (!source.equals(this.serverId)) {
            LOG.debug("Got unexpected message from {}, ignores.", source);
            continue;
          }
        }
        if (msg.getType() != MessageType.HEARTBEAT && LOG.isDebugEnabled()) {
          LOG.debug("Got message {} from {}",
                    TextFormat.shortDebugString(msg), source);
        }
        if (msg.getType() == MessageType.PROPOSAL) {
          Transaction txn = MessageBuilder.fromProposal(msg.getProposal());
          Zxid zxid = txn.getZxid();
          if (zxid.getEpoch() == ackEpoch) {
            // Dispatch to SyncProposalProcessor and CommitProcessor.
            syncProcessor.processRequest(tuple);
            commitProcessor.processRequest(tuple);
          } else {
            LOG.debug("The proposal has the wrong epoch {}, expecting {}.",
                      zxid.getEpoch(), ackEpoch);
            throw new RuntimeException("The proposal has wrong epoch number.");
          }
        } else if (msg.getType() == MessageType.COMMIT) {
          commitProcessor.processRequest(tuple);
        } else if (msg.getType() == MessageType.HEARTBEAT) {
          LOG.trace("Got HEARTBEAT from {}.", source);
          // Replies HEARTBEAT message to leader.
          Message heartbeatReply = MessageBuilder.buildHeartbeat();
          sendMessage(source, heartbeatReply);
        } else if (msg.getType() == MessageType.DELIVERED) {
          onDelivered(msg, snapProcessor);
        } else if (msg.getType() == MessageType.FLUSH) {
          onFlush(tuple, commitProcessor);
        } else {
          if (LOG.isWarnEnabled()) {
            LOG.warn("Unexpected messgae : {} from {}",
                     TextFormat.shortDebugString(msg),
                     source);
          }
        }
      }
    } finally {
      sendTask.shutdown();
      commitProcessor.shutdown();
      syncProcessor.shutdown();
      snapProcessor.shutdown();
      this.lastDeliveredZxid = commitProcessor.getLastDeliveredZxid();
      this.participantState.updateLastDeliveredZxid(this.lastDeliveredZxid);
    }
  }

  // It's possible the cluster already has a huge history. Instead of
  // joining the cluster now, we do the first synchronization to make the
  // new joined server's history 'almost' up-to-date, then issues the
  // JOIN message. Once JOIN message has been issued, the cluster might be
  // blocked (depends on whether the new configuration will have a quorum
  // of servers in broadcasting phase while the synchronization is going
  // on). If the cluster will be blocked, the length of the blocking time
  // depends on the length of the synchronization.
  private void joinSynchronization()
      throws IOException, TimeoutException, InterruptedException {
    Log log = persistence.getLog();
    Message sync = MessageBuilder.buildSyncHistory(log.getLatestZxid());
    sendMessage(this.electedLeader, sync);
    MessageTuple tuple =
      getExpectedMessage(MessageType.SYNC_HISTORY_REPLY, electedLeader);
    Message msg = tuple.getMessage();
    // Updates the sync timeout based on leader's suggestion.
    setSyncTimeoutMs(msg.getSyncHistoryReply().getSyncTimeout());
    // Waits for the first synchronization completes.
    waitForSync(this.electedLeader);

    // Gets the last zxid in log after first synchronization.
    Zxid lastZxid = log.getLatestZxid();
    LOG.debug("After first synchronization, the last zxid is {}", lastZxid);
    // Then issues the JOIN message.
    Message join = MessageBuilder.buildJoin(lastZxid);
    sendMessage(this.electedLeader, join);

    /* -- Synchronizing phase -- */
    changePhase(Phase.SYNCHRONIZING);
    waitForSync(this.electedLeader);
    waitForNewLeaderMessage();
    waitForCommitMessage();
    persistence.setProposedEpoch(persistence.getAckEpoch());
  }
}
