package com.koushik.kvstore.raft;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LogEntry {
    private final long index;    // 1-based position in log
    private final long term;     // term when entry was created
    private final String command; // "PUT key value" or "DELETE key"
}
