package com.koushik.kvstore.raft;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RaftLogTest {

    private RaftLog log;

    @BeforeEach
    void setUp() {
        log = new RaftLog();
    }

    @Test
    void startsWithSentinel() {
        assertEquals(1, log.size()); // sentinel only
        assertEquals(0, log.lastIndex());
        assertEquals(0, log.lastTerm());
    }

    @Test
    void appendAndGet() {
        log.append(new LogEntry(1, 1, "PUT foo bar"));
        log.append(new LogEntry(2, 1, "PUT baz qux"));

        assertEquals(2, log.lastIndex());
        assertEquals(1, log.lastTerm());
        assertEquals("PUT foo bar", log.get(1).getCommand());
        assertEquals("PUT baz qux", log.get(2).getCommand());
    }

    @Test
    void deleteFrom() {
        log.append(new LogEntry(1, 1, "PUT a 1"));
        log.append(new LogEntry(2, 1, "PUT b 2"));
        log.append(new LogEntry(3, 2, "PUT c 3"));

        log.deleteFrom(2); // delete entries at index 2 and beyond
        assertEquals(1, log.lastIndex());
        assertEquals("PUT a 1", log.get(1).getCommand());
    }

    @Test
    void getFrom() {
        log.append(new LogEntry(1, 1, "PUT a 1"));
        log.append(new LogEntry(2, 1, "PUT b 2"));
        log.append(new LogEntry(3, 2, "PUT c 3"));

        List<LogEntry> entries = log.getFrom(2);
        assertEquals(2, entries.size());
        assertEquals("PUT b 2", entries.get(0).getCommand());
        assertEquals("PUT c 3", entries.get(1).getCommand());
    }

    @Test
    void getFromEntireLog() {
        log.append(new LogEntry(1, 1, "PUT a 1"));
        List<LogEntry> entries = log.getFrom(1);
        assertEquals(1, entries.size());
    }

    @Test
    void deleteFromDoesNotRemoveSentinel() {
        log.append(new LogEntry(1, 1, "PUT a 1"));
        log.deleteFrom(1);
        assertEquals(0, log.lastIndex());
        assertEquals(0, log.lastTerm()); // sentinel term
    }
}
