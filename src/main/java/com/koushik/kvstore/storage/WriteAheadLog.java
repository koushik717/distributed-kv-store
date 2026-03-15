package com.koushik.kvstore.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Write-Ahead Log: append-only file that records every committed command.
 * On startup, replay all entries to rebuild in-memory state.
 *
 * Format: one command per line — "PUT key value" or "DELETE key"
 */
@Slf4j
@Component
public class WriteAheadLog {

    private final Path walPath;
    private BufferedWriter writer;

    public WriteAheadLog(@Value("${kv.data-dir:./data}") String dataDir) throws IOException {
        Files.createDirectories(Paths.get(dataDir));
        this.walPath = Paths.get(dataDir, "wal.log");
        this.writer = Files.newBufferedWriter(walPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        log.info("WAL initialized at {}", walPath.toAbsolutePath());
    }

    /**
     * Append a command to the WAL. Flushes immediately — durability over performance.
     */
    public synchronized void append(String command) throws IOException {
        writer.write(command);
        writer.newLine();
        writer.flush(); // critical: ensures data hits disk before we ack the client
    }

    /**
     * Read all commands from WAL on startup to replay state.
     */
    public List<String> readAll() throws IOException {
        if (!Files.exists(walPath)) return Collections.emptyList();
        return Files.readAllLines(walPath);
    }

    /**
     * Truncate WAL — used after a snapshot is taken.
     */
    public synchronized void truncate() throws IOException {
        writer.close();
        writer = Files.newBufferedWriter(walPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
        log.info("WAL truncated");
    }
}
