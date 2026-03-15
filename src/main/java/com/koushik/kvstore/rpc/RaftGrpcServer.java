package com.koushik.kvstore.rpc;

import com.koushik.kvstore.raft.RaftConfig;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;

@Slf4j
@Component
public class RaftGrpcServer {

    private final RaftConfig config;
    private final RaftServiceImpl service;
    private Server server;

    public RaftGrpcServer(RaftConfig config, RaftServiceImpl service) {
        this.config = config;
        this.service = service;
    }

    @PostConstruct
    public void start() throws IOException {
        int port = config.getGrpcPort();
        server = ServerBuilder.forPort(port)
            .addService(service)
            .build()
            .start();
        log.info("gRPC server started on port {}", port);
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.shutdown();
            log.info("gRPC server stopped");
        }
    }
}
