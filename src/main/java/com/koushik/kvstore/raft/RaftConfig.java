package com.koushik.kvstore.raft;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConfigurationProperties(prefix = "kv")
@Getter
@Setter
public class RaftConfig {

    private String nodeId = "node1";
    private String dataDir = "./data";
    private int grpcPort = 9090;
    // Format: "node2:localhost:9091,node3:localhost:9092"
    private String peers = "";

    private final Map<String, String> peerAddresses = new LinkedHashMap<>();

    public void setPeers(String peers) {
        this.peers = peers;
        peerAddresses.clear();
        if (peers == null || peers.isBlank()) return;
        for (String peer : peers.split(",")) {
            String[] parts = peer.split(":");
            if (parts.length >= 3) {
                // parts[0]=nodeId, parts[1]=host, parts[2]=grpcPort
                peerAddresses.put(parts[0].trim(), parts[1].trim() + ":" + parts[2].trim());
            }
        }
    }

    public List<String> getPeerIds() {
        return new ArrayList<>(peerAddresses.keySet());
    }

    public String getPeerAddress(String peerId) {
        return peerAddresses.get(peerId);
    }
}
