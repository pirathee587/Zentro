package com.zentro.notificationintegration.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zentro.notificationintegration.config.AssistantProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

class CustomerMcpClientFactoryTest {
  private Jwt jwt(String subject) {
    return Jwt.withTokenValue("token-" + subject).header("alg", "RS256").subject(subject).build();
  }

  @Test void officialAutoConfigurationCreatesIsolatedClientsCallbacksAndClosesTheirTransports() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    UUID parcelId = UUID.randomUUID();
    server.createContext("/api/v1/", exchange -> {
      String owner = exchange.getRequestHeaders().getFirst("Authorization");
      String result = exchange.getRequestURI().getPath().startsWith("/api/v1/parcels/")
          ? "{\"id\":\"" + parcelId + "\",\"status\":\"" + owner + "\"}"
          : "{\"content\":[{\"trackingRef\":\"" + owner + "\"}]}";
      byte[] data = result.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, data.length);
      exchange.getResponseBody().write(data);
      exchange.close();
    });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    try {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(McpClientAutoConfiguration.class, McpToolCallbackAutoConfiguration.class))
          .withPropertyValues("spring.ai.mcp.client.enabled=false", "spring.ai.mcp.client.name=zentro-support",
              "spring.ai.mcp.client.request-timeout=8s")
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withBean(AssistantProperties.class, () -> new AssistantProperties("", url, "deepseek-chat", url, 1000, 1000, 1000))
          .withBean(CustomerMcpClientFactory.class)
          .run(root -> {
            assertThat(root).hasSingleBean(CustomerMcpClientFactory.class).doesNotHaveBean(McpSyncClient.class)
                .doesNotHaveBean(SyncMcpToolCallbackProvider.class).doesNotHaveBean("mcpSyncClients");
            var factory = root.getBean(CustomerMcpClientFactory.class);
            ConfigurableApplicationContext aliceContext;
            InProcessMcpTransport aliceTransport;
            try (var alice = factory.open(jwt("alice")); var bob = factory.open(jwt("bob"))) {
              aliceContext = (ConfigurableApplicationContext) ReflectionTestUtils.getField(alice, "context");
              var bobContext = (ConfigurableApplicationContext) ReflectionTestUtils.getField(bob, "context");
              assertThat(aliceContext.getBean(McpClientAutoConfiguration.class)).isNotNull();
              assertThat(aliceContext.getBean(McpToolCallbackAutoConfiguration.class)).isNotNull();
              assertThat(aliceContext.getBean(SyncMcpToolCallbackProvider.class)).isNotSameAs(bobContext.getBean(SyncMcpToolCallbackProvider.class));
              assertThat(aliceContext.getBean("mcpSyncClients", List.class)).hasSize(1);
              var aliceClient = (McpSyncClient) aliceContext.getBean("mcpSyncClients", List.class).getFirst();
              var bobClient = (McpSyncClient) bobContext.getBean("mcpSyncClients", List.class).getFirst();
              assertThat(aliceClient).isNotSameAs(bobClient);
              assertThat(aliceClient.getClientInfo().name()).contains("zentro-support");
              assertThat(aliceContext.getBean(McpClientCommonProperties.class).getRequestTimeout()).isEqualTo(Duration.ofSeconds(8));
              var named = (NamedClientMcpTransport) aliceContext.getBean("customerMcpTransports", List.class).getFirst();
              aliceTransport = (InProcessMcpTransport) named.transport();
              var aliceResult = CompletableFuture.supplyAsync(() -> callBothServices(alice, parcelId));
              var bobResult = CompletableFuture.supplyAsync(() -> callBothServices(bob, parcelId));
              assertThat(aliceResult.get(10, TimeUnit.SECONDS)).contains("Bearer token-alice").doesNotContain("token-bob");
              assertThat(bobResult.get(10, TimeUnit.SECONDS)).contains("Bearer token-bob").doesNotContain("token-alice");
            }
            assertThat(aliceContext.isActive()).isFalse();
            var subscription = (reactor.core.Disposable) ReflectionTestUtils.getField(aliceTransport, "subscription");
            assertThat(subscription.isDisposed()).isTrue();
          });
    } finally { server.stop(0); }
  }

  private String callBothServices(CustomerMcpSession session, UUID parcelId) {
    StringBuilder result = new StringBuilder();
    for (var callback : session.toolCallbacks()) {
      String name = callback.getToolDefinition().name();
      if (name.endsWith("get_parcel_details")) result.append(callback.call("{\"parcelId\":\"" + parcelId + "\"}"));
      if (name.endsWith("get_my_deliveries")) result.append(callback.call("{}"));
    }
    return result.toString();
  }
}
