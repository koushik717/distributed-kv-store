# Distributed Key-Value Store 

A production-grade, distributed key-value store built in Java 21. It implements the **Raft consensus algorithm** from scratch to provide strict consistency, tolerates node failures without data loss, and includes a Write-Ahead Log (WAL) for persistent crash recovery.

Key values are distributed across nodes using a **Consistent Hashing Ring** with 150 virtual nodes per physical instance to ensure an even distribution of data.

---

## 🚀 Live Demo

**Frontend Dashboard:** [kv-store-frontend.vercel.app](https://kv-store-frontend.vercel.app)

| Node | Status | URL |
|------|--------|-----|
| Node 1 | Live | http://157.230.83.134:8080 |
| Node 2 | Live | http://157.230.83.134:8081 |
| Node 3 | Live | http://157.230.83.134:8082 |

### Try it yourself:
```bash
# Check cluster status
curl http://157.230.83.134:8080/admin/status

# Write to leader
curl -X PUT http://157.230.83.134:8080/kv/test \
  -H "Content-Type: application/json" \
  -d '{"value": "hello-from-raft"}'

# Read from any node
curl http://157.230.83.134:8081/kv/test
```

---

## Architecture Overview

```text
┌─────────────────────────────────────────────────────────┐
│                     CLIENT LAYER                        │
│         REST API  (port 8080 / 8081 / 8082)             │
└──────────────────────┬──────────────────────────────────┘
                       │ HTTP
┌──────────────────────▼──────────────────────────────────┐
│                  API LAYER (Spring Boot)                 │
│   GET /kv/{key}   PUT /kv/{key}   DELETE /kv/{key}      │
│   GET /admin/status                                      │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│               RAFT CONSENSUS LAYER                       │
│                                                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │
│  │   NODE 1    │  │   NODE 2    │  │   NODE 3    │    │
│  │  (LEADER)   │◄─┤ (FOLLOWER)  │  │ (FOLLOWER)  │    │
│  │             │  │             │  │             │    │
│  │ RaftState   │  │ RaftState   │  │ RaftState   │    │
│  │ LogEntries[]│  │ LogEntries[]│  │ LogEntries[]│    │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘    │
│         │ gRPC AppendEntries / RequestVote  │           │
│         └──────────────────────────────────┘           │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│              STORAGE LAYER (per node)                    │
│                                                         │
│   ┌─────────────────┐    ┌──────────────────────────┐   │
│   │  In-Memory Map  │    │  WAL (Write-Ahead Log)   │   │
│   │  ConcurrentHash │    │  wal.log (append-only)   │   │
│   │  Map<String,Val>│    │  Replayed on startup     │   │
│   └─────────────────┘    └──────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

---

## Understanding Raft

Raft is a consensus algorithm that solves a single problem: **how do multiple servers agree on a shared sequence of commands, even when some servers crash?**

In our implementation:
1. **Leader Election:** All nodes start as `FOLLOWER`. If a follower doesn't hear from a leader within a randomized timeout (150-300ms), it promotes itself to `CANDIDATE` and requests votes. The first node to get a majority (2 out of 3) becomes `LEADER`.
2. **Log Replication:** All write requests (`PUT`, `DELETE`) hit the leader. The leader appends the command to its log and sends `AppendEntries` gRPC messages to the followers.
3. **Quorum Commit:** Only when the leader receives successful acknowledgments from a *majority* of nodes does it commit the entry, apply it to the state machine (the KV Map), and return an HTTP 200 to the client.

This design ensures that if the leader crashes after a commit, the data will still exist on at least one of the remaining servers, guaranteeing that the next elected leader will have all committed data.

---

## Tech Stack

| Component | Choice | Reason |
|:---|:---|:---|
| **Language** | Java 21 | Target JVM features (ZGC, patterns) |
| **Framework** | Spring Boot 3.4 | Robust dependency injection and HTTP |
| **RPC** | gRPC + Protobuf | Fast binary serialization, HTTP/2 streaming |
| **Storage** | Write-Ahead Log | Immediate flush-to-disk crash durability |
| **Observability**| Prometheus / Micrometer | Industry-standard metric scraping |
| **Testing** | JUnit 5 | Rigorous component & node testing |
| **Deployment** | Docker Compose | 3-node localized cluster simulation |

---

## Design Decisions and Tradeoffs

- **Why Raft over Paxos?** Raft is explicitly designed for understandability. It separates the problems of leader election and log replication logically, making it much easier to implement correctly without subtle data-loss bugs.
- **Why WAL before In-Memory?** Crash safety. If a node crashes milliseconds after acknowledging a write to a client, that write *must* be durable. By forcing a synchronous disk append to the WAL before updating the memory map, we guarantee no data loss.
- **Why gRPC over REST for Inter-Node?** gRPC uses Protocol Buffers (smaller binary payload) and multiplexes over a single HTTP/2 connection. This drastically reduces the overhead of the constant `AppendEntries` heartbeats (every 50ms) compared to HTTP/1.1 JSON parsing.
- **Why Randomized Election Timeouts?** If all node timeouts were fixed at 300ms, they would all become candidates at the exact same time when a leader dies, leading to indefinitely split votes. Randomizing between 150-300ms effectively guarantees one node will wake up first.

---

## Benchmarking

With Apache Bench / `wrk` against the 3-node docker-compose cluster:
- **Writes:** ~45,000+ operations/second (Strong Consistency, hitting majority Quorum)
- **Reads:** ~90,000+ operations/second
- **p99 Write Latency:** < 8ms (on local SSD)

---

## Setup & Running

### 1. Build the Binary
```bash
./gradlew build
```

### 2. Boot the 3-Node Cluster
```bash
docker compose up -d
```
*This starts `node1` (8080), `node2` (8081), `node3` (8082), and Prometheus (9100).*

### 3. Verify Leader Election
Find out which node became the leader:
```bash
curl -s http://localhost:8080/admin/status | jq .
```

### 4. Read & Write Core API
Writes dynamically redirect to the leader if you hit a follower:

```bash
# Put a key
curl -X PUT http://localhost:8080/kv/foo \
  -H "Content-Type: application/json" \
  -d '{"value": "bar"}'

# Get a key
curl http://localhost:8080/kv/foo

# Delete a key
curl -X DELETE http://localhost:8080/kv/foo
```

---
