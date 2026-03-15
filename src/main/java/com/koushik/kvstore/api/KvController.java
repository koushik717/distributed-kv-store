package com.koushik.kvstore.api;

import com.koushik.kvstore.raft.RaftNode;
import com.koushik.kvstore.storage.InMemoryKvStore;
import com.koushik.kvstore.metrics.KvMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/kv")
@RequiredArgsConstructor
public class KvController {

    private final InMemoryKvStore store;
    private final KvMetrics metrics;
    private final RaftNode raftNode;

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, String>> get(@PathVariable String key) {
        metrics.incrementRead();
        return store.get(key)
            .map(value -> ResponseEntity.ok(Map.of("key", key, "value", value)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> put(@PathVariable String key,
                                 @RequestBody Map<String, String> body) {
        if (!raftNode.isLeader()) {
            String leader = raftNode.getLeaderHttpAddress();
            if (leader != null) {
                return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .header("Location", "http://" + leader + "/kv/" + key)
                    .build();
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "No leader elected yet"));
        }

        String value = body.get("value");
        if (value == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Missing 'value' in request body"));
        }

        metrics.incrementWrite();
        String command = "PUT " + key + " " + value;

        try {
            boolean committed = raftNode.submitCommand(command).get(5, TimeUnit.SECONDS);
            if (committed) {
                return ResponseEntity.ok(Map.of("key", key, "value", value));
            }
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("error", "Commit timeout"));
        } catch (Exception e) {
            log.error("Error processing PUT for key {}", key, e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<?> delete(@PathVariable String key) {
        if (!raftNode.isLeader()) {
            String leader = raftNode.getLeaderHttpAddress();
            if (leader != null) {
                return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .header("Location", "http://" + leader + "/kv/" + key)
                    .build();
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "No leader elected yet"));
        }

        metrics.incrementDelete();
        String command = "DELETE " + key;

        try {
            boolean committed = raftNode.submitCommand(command).get(5, TimeUnit.SECONDS);
            if (committed) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("error", "Commit timeout"));
        } catch (Exception e) {
            log.error("Error processing DELETE for key {}", key, e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> getAll() {
        return ResponseEntity.ok(store.getAll());
    }
}
