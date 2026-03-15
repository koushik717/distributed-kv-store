package com.koushik.kvstore.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class InMemoryKvStore {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    private final WriteAheadLog wal;

    public InMemoryKvStore(WriteAheadLog wal) {
        this.wal = wal;
    }

    /**
     * On startup: replay WAL to rebuild state.
     * This is how we survive crashes without losing data.
     */
    @PostConstruct
    public void replayWal() throws IOException {
        List<String> commands = wal.readAll();
        log.info("Replaying {} WAL entries on startup", commands.size());
        for (String command : commands) {
            applyCommand(command);
        }
    }

    /**
     * Apply a command to state. Called both during WAL replay and live writes.
     * Commands: "PUT key value" or "DELETE key"
     */
    public void applyCommand(String command) {
        String[] parts = command.split(" ", 3);
        switch (parts[0]) {
            case "PUT" -> store.put(parts[1], parts[2]);
            case "DELETE" -> store.remove(parts[1]);
            default -> log.warn("Unknown command: {}", command);
        }
    }

    /**
     * Write path: persist to WAL first, then apply to memory.
     * NEVER apply to memory before WAL — crash safety.
     */
    public void put(String key, String value) throws IOException {
        String command = "PUT " + key + " " + value;
        wal.append(command);         // 1. write to disk first
        store.put(key, value);       // 2. then update memory
    }

    public void delete(String key) throws IOException {
        String command = "DELETE " + key;
        wal.append(command);
        store.remove(key);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(store.get(key));
    }

    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(store);
    }

    public int size() {
        return store.size();
    }
}
