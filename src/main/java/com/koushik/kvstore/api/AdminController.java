package com.koushik.kvstore.api;

import com.koushik.kvstore.raft.RaftNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final RaftNode raftNode;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "nodeId", raftNode.getNodeId(),
            "state", raftNode.getState().name(),
            "term", raftNode.getCurrentTerm(),
            "commitIndex", raftNode.getCommitIndex(),
            "leader", raftNode.getCurrentLeader() != null
                ? raftNode.getCurrentLeader() : "unknown"
        );
    }
}
