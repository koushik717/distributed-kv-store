package com.koushik.kvstore.raft;

import com.koushik.kvstore.rpc.RaftGrpcClient;
import com.koushik.kvstore.storage.InMemoryKvStore;
import com.koushik.kvstore.metrics.KvMetrics;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Slf4j
@Component
public class RaftNode {

    // ── Persistent state (survive restarts — in production, persist to disk)
    @Getter private volatile long currentTerm = 0;
    private volatile String votedFor = null;
    @Getter private final RaftLog raftLog = new RaftLog();

    // ── Volatile state (all nodes)
    @Getter private volatile long commitIndex = 0;
    private volatile long lastApplied = 0;

    // ── Volatile state (leader only — reinitialized after election)
    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();

    // ── Node identity
    @Getter private final String nodeId;
    private final List<String> peerIds;

    // ── State
    @Getter private volatile RaftState state = RaftState.FOLLOWER;
    @Getter private volatile String currentLeader = null;

    // ── Timers
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private ScheduledFuture<?> electionTimer;
    private ScheduledFuture<?> heartbeatTimer;
    private final Random random = new Random();

    // ── Timing constants (milliseconds)
    private static final int HEARTBEAT_INTERVAL_MS = 50;
    private static final int ELECTION_TIMEOUT_MIN_MS = 150;
    private static final int ELECTION_TIMEOUT_MAX_MS = 300;

    // ── Dependencies
    private final RaftGrpcClient grpcClient;
    private final InMemoryKvStore kvStore;
    private final KvMetrics metrics;
    private final RaftConfig config;

    public RaftNode(RaftConfig config, RaftGrpcClient grpcClient,
                    InMemoryKvStore kvStore, KvMetrics metrics) {
        this.nodeId = config.getNodeId();
        this.peerIds = config.getPeerIds();
        this.grpcClient = grpcClient;
        this.kvStore = kvStore;
        this.metrics = metrics;
        this.config = config;
    }

    @PostConstruct
    public void start() {
        log.info("RaftNode {} starting as FOLLOWER with {} peers", nodeId, peerIds.size());
        if (!peerIds.isEmpty()) {
            resetElectionTimer();
        } else {
            // Single node — become leader immediately
            log.info("No peers configured — single node mode, becoming LEADER");
            currentTerm = 1;
            state = RaftState.LEADER;
            currentLeader = nodeId;
            metrics.setRaftTerm(currentTerm);
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
        grpcClient.shutdown();
    }

    // ════════════════════════════════════════════════════════════
    // ELECTION TIMER
    // ════════════════════════════════════════════════════════════

    private synchronized void resetElectionTimer() {
        if (electionTimer != null) electionTimer.cancel(false);
        int timeout = ELECTION_TIMEOUT_MIN_MS +
            random.nextInt(ELECTION_TIMEOUT_MAX_MS - ELECTION_TIMEOUT_MIN_MS);
        electionTimer = scheduler.schedule(this::startElection, timeout, TimeUnit.MILLISECONDS);
    }

    // ════════════════════════════════════════════════════════════
    // LEADER ELECTION
    // ════════════════════════════════════════════════════════════

    private synchronized void startElection() {
        if (state == RaftState.LEADER) return;

        currentTerm++;
        state = RaftState.CANDIDATE;
        votedFor = nodeId;
        currentLeader = null;

        log.info("Node {} starting election for term {}", nodeId, currentTerm);
        metrics.setRaftTerm(currentTerm);

        AtomicInteger votes = new AtomicInteger(1); // our own vote
        int majority = (peerIds.size() + 1) / 2 + 1;
        long termForRequest = currentTerm;
        long lastLogIndex = raftLog.lastIndex();
        long lastLogTerm = raftLog.lastTerm();

        for (String peer : peerIds) {
            CompletableFuture.runAsync(() -> {
                try {
                    boolean granted = grpcClient.requestVote(
                        peer, termForRequest, nodeId, lastLogIndex, lastLogTerm
                    );
                    if (granted) {
                        int total = votes.incrementAndGet();
                        if (total >= majority) {
                            synchronized (RaftNode.this) {
                                if (currentTerm == termForRequest && state == RaftState.CANDIDATE) {
                                    becomeLeader();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("RequestVote to {} failed: {}", peer, e.getMessage());
                }
            }, scheduler);
        }

        // Reset timer in case election fails (split vote)
        resetElectionTimer();
    }

    private synchronized void becomeLeader() {
        if (state != RaftState.CANDIDATE) return;

        state = RaftState.LEADER;
        currentLeader = nodeId;
        log.info("🎉 Node {} became LEADER for term {}", nodeId, currentTerm);

        // Initialize leader state
        for (String peer : peerIds) {
            nextIndex.put(peer, raftLog.lastIndex() + 1);
            matchIndex.put(peer, 0L);
        }

        // Cancel election timer, start heartbeat
        if (electionTimer != null) electionTimer.cancel(false);
        heartbeatTimer = scheduler.scheduleAtFixedRate(
            this::sendHeartbeats, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ════════════════════════════════════════════════════════════
    // HEARTBEATS & LOG REPLICATION
    // ════════════════════════════════════════════════════════════

    private void sendHeartbeats() {
        if (state != RaftState.LEADER) return;
        for (String peer : peerIds) {
            replicateLog(peer);
        }
    }

    private void replicateLog(String peer) {
        long ni = nextIndex.getOrDefault(peer, 1L);
        long prevLogIndex = ni - 1;
        long prevLogTerm = prevLogIndex > 0 ? raftLog.get(prevLogIndex).getTerm() : 0;

        List<LogEntry> entries = ni <= raftLog.lastIndex()
            ? raftLog.getFrom(ni)
            : Collections.emptyList();

        long currentCommit = commitIndex;
        long termSnapshot = currentTerm;

        CompletableFuture.runAsync(() -> {
            try {
                boolean success = grpcClient.appendEntries(
                    peer, termSnapshot, nodeId,
                    prevLogIndex, prevLogTerm,
                    entries, currentCommit
                );

                synchronized (this) {
                    if (state != RaftState.LEADER || currentTerm != termSnapshot) return;

                    if (success) {
                        if (!entries.isEmpty()) {
                            long newMatchIndex = prevLogIndex + entries.size();
                            matchIndex.put(peer, newMatchIndex);
                            nextIndex.put(peer, newMatchIndex + 1);
                            tryCommit();
                        }
                    } else {
                        // Follower rejected — back up nextIndex and retry
                        long backed = Math.max(1, nextIndex.getOrDefault(peer, 1L) - 1);
                        nextIndex.put(peer, backed);
                    }
                }
            } catch (Exception e) {
                log.debug("AppendEntries to {} failed: {}", peer, e.getMessage());
            }
        }, scheduler);
    }

    private synchronized void tryCommit() {
        long lastIndex = raftLog.lastIndex();
        for (long n = lastIndex; n > commitIndex; n--) {
            if (raftLog.get(n).getTerm() != currentTerm) continue;
            long finalN = n;
            long count = 1 + matchIndex.values().stream()
                .filter(mi -> mi >= finalN).count();
            int majority = (peerIds.size() + 1) / 2 + 1;
            if (count >= majority) {
                commitIndex = n;
                applyCommitted();
                metrics.setLogIndex(commitIndex);
                break;
            }
        }
    }

    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            String command = raftLog.get(lastApplied).getCommand();
            try {
                kvStore.applyCommand(command);
                log.debug("Applied command at index {}: {}", lastApplied, command);
            } catch (Exception e) {
                log.error("Failed to apply command: {}", command, e);
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // INCOMING RPC HANDLERS
    // ════════════════════════════════════════════════════════════

    public synchronized RequestVoteResult handleRequestVote(
            long term, String candidateId, long lastLogIndex, long lastLogTerm) {

        if (term > currentTerm) stepDown(term);

        if (term < currentTerm) {
            return new RequestVoteResult(currentTerm, false);
        }

        boolean logUpToDate = (lastLogTerm > raftLog.lastTerm()) ||
            (lastLogTerm == raftLog.lastTerm() && lastLogIndex >= raftLog.lastIndex());

        boolean canVote = (votedFor == null || votedFor.equals(candidateId)) && logUpToDate;

        if (canVote) {
            votedFor = candidateId;
            resetElectionTimer();
            log.info("Node {} voting for {} in term {}", nodeId, candidateId, term);
        }

        return new RequestVoteResult(currentTerm, canVote);
    }

    public synchronized AppendEntriesResult handleAppendEntries(
            long term, String leaderId, long prevLogIndex, long prevLogTerm,
            List<LogEntry> entries, long leaderCommit) {

        if (term < currentTerm) {
            return new AppendEntriesResult(currentTerm, false);
        }

        if (term > currentTerm || state != RaftState.FOLLOWER) {
            stepDown(term);
        }

        // Valid RPC from leader — reset election timer
        currentLeader = leaderId;
        resetElectionTimer();

        // Convert to FOLLOWER if we were a candidate
        if (state == RaftState.CANDIDATE) {
            state = RaftState.FOLLOWER;
        }

        // Check log consistency
        if (prevLogIndex > 0) {
            if (raftLog.lastIndex() < prevLogIndex) {
                return new AppendEntriesResult(currentTerm, false);
            }
            if (raftLog.get(prevLogIndex).getTerm() != prevLogTerm) {
                raftLog.deleteFrom(prevLogIndex);
                return new AppendEntriesResult(currentTerm, false);
            }
        }

        // Append new entries (skip if already have them)
        for (LogEntry entry : entries) {
            if (entry.getIndex() <= raftLog.lastIndex()) {
                if (raftLog.get(entry.getIndex()).getTerm() != entry.getTerm()) {
                    raftLog.deleteFrom(entry.getIndex());
                }
            }
            if (entry.getIndex() > raftLog.lastIndex()) {
                raftLog.append(entry);
            }
        }

        // Update commitIndex
        if (leaderCommit > commitIndex) {
            commitIndex = Math.min(leaderCommit, raftLog.lastIndex());
            applyCommitted();
        }

        return new AppendEntriesResult(currentTerm, true);
    }

    // ════════════════════════════════════════════════════════════
    // CLIENT WRITE ENTRY POINT
    // ════════════════════════════════════════════════════════════

    public CompletableFuture<Boolean> submitCommand(String command) {
        if (state != RaftState.LEADER) {
            return CompletableFuture.completedFuture(false);
        }

        long index;
        synchronized (this) {
            index = raftLog.lastIndex() + 1;
            LogEntry entry = new LogEntry(index, currentTerm, command);
            raftLog.append(entry);
            metrics.setLogIndex(index);
        }

        // If single node, commit immediately
        if (peerIds.isEmpty()) {
            synchronized (this) {
                commitIndex = index;
                applyCommitted();
                metrics.setLogIndex(commitIndex);
            }
            return CompletableFuture.completedFuture(true);
        }

        // Trigger replication
        for (String peer : peerIds) {
            replicateLog(peer);
        }

        // Wait for commit (poll commitIndex)
        long targetIndex = index;
        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                if (commitIndex >= targetIndex) return true;
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
            }
            return false;
        }, scheduler);
    }

    // ════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════

    private void stepDown(long newTerm) {
        log.info("Node {} stepping down to FOLLOWER at term {}", nodeId, newTerm);
        currentTerm = newTerm;
        state = RaftState.FOLLOWER;
        votedFor = null;
        currentLeader = null;
        if (heartbeatTimer != null) heartbeatTimer.cancel(false);
        metrics.setRaftTerm(currentTerm);
        resetElectionTimer();
    }

    public boolean isLeader() { return state == RaftState.LEADER; }

    public String getLeaderHttpAddress() {
        if (currentLeader == null) return null;
        if (currentLeader.equals(nodeId)) return null;
        String grpcAddr = config.getPeerAddress(currentLeader);
        if (grpcAddr == null) return null;
        // Convention: HTTP port = gRPC port - 1010 (9090→8080, 9091→8081, etc.)
        String[] parts = grpcAddr.split(":");
        int grpcPort = Integer.parseInt(parts[1]);
        int httpPort = grpcPort - 1010;
        return parts[0] + ":" + httpPort;
    }

    // Result types
    public record RequestVoteResult(long term, boolean voteGranted) {}
    public record AppendEntriesResult(long term, boolean success) {}
}
