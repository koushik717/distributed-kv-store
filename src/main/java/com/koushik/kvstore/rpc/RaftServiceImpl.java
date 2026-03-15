package com.koushik.kvstore.rpc;

import com.koushik.kvstore.raft.*;
import com.koushik.kvstore.rpc.proto.*;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {

    private final RaftNode raftNode;

    @Override
    public void requestVote(RaftProto.RequestVoteRequest req,
                            StreamObserver<RaftProto.RequestVoteResponse> responseObserver) {
        RaftNode.RequestVoteResult result = raftNode.handleRequestVote(
            req.getTerm(),
            req.getCandidateId(),
            req.getLastLogIndex(),
            req.getLastLogTerm()
        );
        responseObserver.onNext(RaftProto.RequestVoteResponse.newBuilder()
            .setTerm(result.term())
            .setVoteGranted(result.voteGranted())
            .build());
        responseObserver.onCompleted();
    }

    @Override
    public void appendEntries(RaftProto.AppendEntriesRequest req,
                              StreamObserver<RaftProto.AppendEntriesResponse> responseObserver) {
        var entries = req.getEntriesList().stream()
            .map(e -> new LogEntry(e.getIndex(), e.getTerm(), e.getCommand()))
            .collect(Collectors.toList());

        RaftNode.AppendEntriesResult result = raftNode.handleAppendEntries(
            req.getTerm(), req.getLeaderId(),
            req.getPrevLogIndex(), req.getPrevLogTerm(),
            entries, req.getLeaderCommit()
        );
        responseObserver.onNext(RaftProto.AppendEntriesResponse.newBuilder()
            .setTerm(result.term())
            .setSuccess(result.success())
            .build());
        responseObserver.onCompleted();
    }
}
