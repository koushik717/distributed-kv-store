package com.koushik.kvstore.cluster;

import java.util.*;

/**
 * Consistent hashing ring.
 * Each node gets 150 virtual nodes to ensure even key distribution.
 * When a node is added/removed, only K/N keys need to be remapped
 * (vs all keys in naive modulo hashing).
 */
public class ConsistentHashRing {

    private static final int VIRTUAL_NODES = 150;
    private final TreeMap<Integer, String> ring = new TreeMap<>();

    public void addNode(String nodeId) {
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            int hash = hash(nodeId + "-vnode-" + i);
            ring.put(hash, nodeId);
        }
    }

    public void removeNode(String nodeId) {
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            int hash = hash(nodeId + "-vnode-" + i);
            ring.remove(hash);
        }
    }

    /**
     * Get the node responsible for a given key.
     * Walks clockwise around the ring to find the first node.
     */
    public String getNode(String key) {
        if (ring.isEmpty()) throw new IllegalStateException("Ring is empty");
        int hash = hash(key);
        Map.Entry<Integer, String> entry = ring.ceilingEntry(hash);
        return entry != null ? entry.getValue() : ring.firstEntry().getValue();
    }

    public int size() {
        return ring.size();
    }

    /**
     * FNV-1a hash — fast and good distribution.
     */
    private int hash(String key) {
        int hash = 0x811c9dc5;
        for (byte b : key.getBytes()) {
            hash ^= b;
            hash *= 0x01000193;
        }
        return hash;
    }
}
