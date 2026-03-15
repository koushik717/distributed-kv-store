package com.koushik.kvstore.raft;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe Raft log.
 * Index is 1-based (index 0 is a sentinel empty entry).
 */
public class RaftLog {

    private final List<LogEntry> entries = new ArrayList<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public RaftLog() {
        // Sentinel at index 0 — simplifies boundary checks
        entries.add(new LogEntry(0, 0, "SENTINEL"));
    }

    public void append(LogEntry entry) {
        lock.writeLock().lock();
        try { entries.add(entry); }
        finally { lock.writeLock().unlock(); }
    }

    public LogEntry get(long index) {
        lock.readLock().lock();
        try { return entries.get((int) index); }
        finally { lock.readLock().unlock(); }
    }

    public long lastIndex() {
        lock.readLock().lock();
        try { return entries.size() - 1; }
        finally { lock.readLock().unlock(); }
    }

    public long lastTerm() {
        lock.readLock().lock();
        try { return entries.get(entries.size() - 1).getTerm(); }
        finally { lock.readLock().unlock(); }
    }

    /**
     * Delete all entries after (and including) fromIndex.
     * Called when follower receives conflicting entries from new leader.
     */
    public void deleteFrom(long fromIndex) {
        lock.writeLock().lock();
        try {
            while (entries.size() > fromIndex) {
                entries.remove(entries.size() - 1);
            }
        } finally { lock.writeLock().unlock(); }
    }

    /**
     * Get entries from startIndex to end of log (inclusive).
     */
    public List<LogEntry> getFrom(long startIndex) {
        lock.readLock().lock();
        try {
            return new ArrayList<>(entries.subList((int) startIndex, entries.size()));
        } finally { lock.readLock().unlock(); }
    }

    public int size() {
        lock.readLock().lock();
        try { return entries.size(); }
        finally { lock.readLock().unlock(); }
    }
}
