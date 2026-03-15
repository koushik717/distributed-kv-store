package com.koushik.kvstore.cluster;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashRingTest {

    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing();
    }

    @Test
    void emptyRingThrows() {
        assertThrows(IllegalStateException.class, () -> ring.getNode("any-key"));
    }

    @Test
    void singleNodeGetsAllKeys() {
        ring.addNode("node1");
        for (int i = 0; i < 100; i++) {
            assertEquals("node1", ring.getNode("key-" + i));
        }
    }

    @Test
    void keysDistributedAcrossNodes() {
        ring.addNode("node1");
        ring.addNode("node2");
        ring.addNode("node3");

        Map<String, Integer> distribution = new HashMap<>();
        int totalKeys = 10000;
        for (int i = 0; i < totalKeys; i++) {
            String node = ring.getNode("key-" + i);
            distribution.merge(node, 1, Integer::sum);
        }

        // Each node should get roundly a third of keys, but 15% is safe for 150 vnodes
        assertEquals(3, distribution.size());
        for (int count : distribution.values()) {
            assertTrue(count > totalKeys * 0.15,
                "Node should get at least 15% of keys, got " + count);
        }
    }

    @Test
    void removeNodeRedistributes() {
        ring.addNode("node1");
        ring.addNode("node2");
        ring.addNode("node3");

        // Record some key assignments before removal
        String keyBefore = ring.getNode("test-key");

        ring.removeNode("node2");

        // Key should now map to either node1 or node3
        String keyAfter = ring.getNode("test-key");
        assertTrue(keyAfter.equals("node1") || keyAfter.equals("node3"));
    }

    @Test
    void deterministic() {
        ring.addNode("node1");
        ring.addNode("node2");

        String first = ring.getNode("consistent-key");
        String second = ring.getNode("consistent-key");
        assertEquals(first, second);
    }
}
