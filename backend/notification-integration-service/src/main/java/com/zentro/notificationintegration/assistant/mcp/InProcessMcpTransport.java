package com.zentro.notificationintegration.assistant.mcp;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.*;
import java.util.List;
import java.util.function.Function;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Request-scoped MCP JSON-RPC transport; no network listener or shared customer session. */
public final class InProcessMcpTransport implements McpClientTransport, McpServerTransportProvider {
  private final ObjectMapper mapper;
  private final Sinks.Many<McpSchema.JSONRPCMessage> replies = Sinks.many().unicast().onBackpressureBuffer();
  private McpServerSession session;
  private Disposable subscription;

  public InProcessMcpTransport(ObjectMapper mapper) { this.mapper = mapper; }

  @Override public List<String> protocolVersions() { return McpClientTransport.super.protocolVersions(); }

  @Override public void setSessionFactory(McpServerSession.Factory factory) {
    session = factory.create(new McpServerTransport() {
      @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> emitReply(message));
      }
      @Override public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
        return InProcessMcpTransport.this.unmarshalFrom(value, type);
      }
      @Override public Mono<Void> closeGracefully() { return Mono.empty(); }
    });
  }

  // The session can send a notification and a tool response on different threads.
  private synchronized void emitReply(McpSchema.JSONRPCMessage message) {
    replies.emitNext(message, Sinks.EmitFailureHandler.FAIL_FAST);
  }

  @Override public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    return Mono.fromRunnable(() -> subscription = replies.asFlux().flatMap(m -> handler.apply(Mono.just(m))).subscribe());
  }
  @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) { return session.handle(message); }
  @Override public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
    return mapper.convertValue(value, mapper.constructType(type.getType()));
  }
  @Override public Mono<Void> notifyClients(String method, Object params) { return session.sendNotification(method, params); }
  @Override public void close() { closeGracefully().block(); }
  @Override public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(() -> { if (subscription != null) subscription.dispose(); });
  }
}
