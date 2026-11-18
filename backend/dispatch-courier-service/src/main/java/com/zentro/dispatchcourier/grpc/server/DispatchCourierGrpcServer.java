package com.zentro.dispatchcourier.grpc.server;

import com.zentro.dispatchcourier.grpc.security.DispatchGrpcSecurityInterceptor;
import com.zentro.dispatchcourier.grpc.service.DispatchCourierGrpcService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "grpc.server.enabled", havingValue = "true", matchIfMissing = true)
public class DispatchCourierGrpcServer {
  private static final Logger log = LoggerFactory.getLogger(DispatchCourierGrpcServer.class);

  private final int port;
  private final DispatchCourierGrpcService service;
  private final DispatchGrpcSecurityInterceptor securityInterceptor;
  private Server server;

  public DispatchCourierGrpcServer(
      @Value("${grpc.server.port:9083}") int port,
      DispatchCourierGrpcService service,
      DispatchGrpcSecurityInterceptor securityInterceptor) {
    this.port = port;
    this.service = service;
    this.securityInterceptor = securityInterceptor;
  }

  @PostConstruct
  public void start() throws IOException {
    server = ServerBuilder.forPort(port)
        .addService(service)
        .intercept(securityInterceptor)
        .build()
        .start();
    log.info("DispatchCourier gRPC server started, listening on port {}", server.getPort());
  }

  @PreDestroy
  public void stop() {
    if (server != null) {
      server.shutdown();
      try {
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
          server.shutdownNow();
        }
      } catch (InterruptedException e) {
        server.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  public int getPort() {
    return server != null ? server.getPort() : port;
  }
}
