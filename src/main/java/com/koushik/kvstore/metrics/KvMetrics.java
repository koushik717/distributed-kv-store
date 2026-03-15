package com.koushik.kvstore.metrics;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class KvMetrics {

    private final Counter reads;
    private final Counter writes;
    private final Counter deletes;
    private final AtomicLong raftTerm = new AtomicLong(0);
    private final AtomicLong logIndex = new AtomicLong(0);

    public KvMetrics(MeterRegistry registry) {
        reads = Counter.builder("kv.operations")
            .tag("type", "read").register(registry);
        writes = Counter.builder("kv.operations")
            .tag("type", "write").register(registry);
        deletes = Counter.builder("kv.operations")
            .tag("type", "delete").register(registry);

        Gauge.builder("raft.term", raftTerm, AtomicLong::get)
            .description("Current Raft term").register(registry);
        Gauge.builder("raft.log_index", logIndex, AtomicLong::get)
            .description("Last log index").register(registry);
    }

    public void incrementRead() { reads.increment(); }
    public void incrementWrite() { writes.increment(); }
    public void incrementDelete() { deletes.increment(); }
    public void setRaftTerm(long term) { raftTerm.set(term); }
    public void setLogIndex(long index) { logIndex.set(index); }
}
