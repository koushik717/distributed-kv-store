package com.koushik.kvstore.rpc;

import com.koushik.kvstore.raft.*;
import com.koushik.kvstore.rpc.proto.*;
import io.grpc.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RaftGrpcClient {

    private final RaftConfig config;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, RaftServiceGrpc.RaftServiceBlockingStub> stubs = new ConcurrentHashMap<>();

    public RaftGrpcClient(RaftConfig config) {
        this.config = config;
    }

    private RaftServiceGrpc.RaftServiceBlockingStub getStub(String peerId) {
        String address = config.getPeerAddress(peerId);
        if (address == null) {
            throw new IllegalArgumentException("Unknown peer: " + peerId);
        }
        return stubs.computeIfAbsent(peerId, id -> {
            String[] parts = address.split(":");
            ManagedChannel channel = ManagedChannelBuilder
                .forAddress(parts[0], Integer.parseInt(parts[1]))
                .usePlaintext()
                .build();
            channels.put(id, channel);
            return RaftServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(500, TimeUnit.MILLISECONDS);
        });
    }

    public boolean requestVote(String peerId, long term, String candidateId,
                               long lastLogIndex, long lastLogTerm) {
        try {
            RaftProto.RequestVoteRequest req = RaftProto.RequestVoteRequest.newBuilder()
                .setTerm(term)
                .setCandidateId(candidateId)
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();
            RaftProto.RequestVoteResponse resp = getStub(peerId).requestVote(req);
            return resp.getVoteGranted();
        } catch (Exception e) {
            log.debug("RequestVote to {} failed: {}", peerId, e.getMessage());
            // Remove stale stub so it gets recreated with fresh deadline
            stubs.remove(peerId);
            return false;
        }
    }

    public boolean appendEntries(String peerId, long term, String leaderId,
                                 long prevLogIndex, long prevLogTerm,
                                 List<LogEntry> entries, long leaderCommit) {
        try {
            var protoEntries = entries.stream().map(e ->
                RaftProto.LogEntry.newBuilder()
                    .setIndex(e.getIndex())
                    .setTerm(e.getTerm())
                    .setCommand(e.getCommand())
                    .build()
            ).collect(Collectors.toList());

            RaftProto.AppendEntriesRequest req = RaftProto.AppendEntriesRequest.newBuilder()
                .setTerm(term)
                .setLeaderId(leaderId)
                .setPrevLogIndex(prevLogIndex)
                .setPrevLogTerm(prevLogTerm)
                .addAllEntries(protoEntries)
                .setLeaderCommit(leaderCommit)
                .build();
            RaftProto.AppendEntriesResponse resp = getStub(peerId).appendEntries(req);
            return resp.getSuccess();
        } catch (Exception e) {
            log.debug("AppendEntries to {} failed: {}", peerId, e.getMessage());
            stubs.remove(peerId);
            return false;
        }
    }

    public void shutdown() {
        channels.values().forEach(ch -> {
            try { ch.shutdown().awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        });
    }
}
