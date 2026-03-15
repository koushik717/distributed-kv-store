package com.koushik.kvstore.storage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalTest {

    @TempDir
    Path tempDir;

    private WriteAheadLog wal;

    @BeforeEach
    void setUp() throws IOException {
        wal = new WriteAheadLog(tempDir.toString());
    }

    @Test
    void appendAndReadAll() throws IOException {
        wal.append("PUT foo bar");
        wal.append("PUT baz qux");
        wal.append("DELETE foo");

        List<String> commands = wal.readAll();
        assertEquals(3, commands.size());
        assertEquals("PUT foo bar", commands.get(0));
        assertEquals("PUT baz qux", commands.get(1));
        assertEquals("DELETE foo", commands.get(2));
    }

    @Test
    void readAllOnEmptyWal() throws IOException {
        List<String> commands = wal.readAll();
        // WAL file is created on construction, so it exists but is empty
        assertTrue(commands.isEmpty());
    }

    @Test
    void truncateClearsWal() throws IOException {
        wal.append("PUT key1 value1");
        wal.append("PUT key2 value2");
        wal.truncate();

        List<String> commands = wal.readAll();
        assertTrue(commands.isEmpty());
    }

    @Test
    void appendAfterTruncate() throws IOException {
        wal.append("PUT old old");
        wal.truncate();
        wal.append("PUT new new");

        List<String> commands = wal.readAll();
        assertEquals(1, commands.size());
        assertEquals("PUT new new", commands.get(0));
    }

    @Test
    void walSurvivesRecreation() throws IOException {
        wal.append("PUT persist me");
        // Simulate restart — create a new WAL instance pointing at same dir
        WriteAheadLog wal2 = new WriteAheadLog(tempDir.toString());
        List<String> commands = wal2.readAll();
        assertEquals(1, commands.size());
        assertEquals("PUT persist me", commands.get(0));
    }
}
