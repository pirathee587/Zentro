package com.zentro.notificationintegration.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.contracts.parcel.v1.CustomerParcelIdRequest;
import com.zentro.contracts.parcel.v1.CustomerParcelPageResponse;
import com.zentro.contracts.parcel.v1.CustomerParcelViewResponse;
import com.zentro.contracts.parcel.v1.ListCustomerParcelsRequest;
import com.zentro.contracts.parcel.v1.ParcelPricingServiceGrpc;
import com.zentro.notificationintegration.config.AssistantProperties;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.modelcontextprotocol.client.McpSyncClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

class CustomerMcpClientFactoryTest {
  private Jwt jwt(String subject) {
    return Jwt.withTokenValue("token-" + subject).header("alg", "RS256").subject(subject).build();
  }

  @Test
  void officialAutoConfigurationCreatesIsolatedClientsCallbacksAndClosesTheirTransports() throws Exception {
    UUID parcelId = UUID.randomUUID();
    String serverName = InProcessServerBuilder.generateName();
    Server grpcServer = InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(new ParcelPricingServiceGrpc.ParcelPricingServiceImplBase() {
          @Override
          public void getCustomerParcelById(
              CustomerParcelIdRequest req, StreamObserver<CustomerParcelViewResponse> obs) {
            obs.onNext(CustomerParcelViewResponse.newBuilder()
                .setId(parcelId.toString())
                .setStatus("sub-" + req.getKeycloakSubject())
                .build());
            obs.onCompleted();
          }

          @Override
          public void listCustomerParcels(
              ListCustomerParcelsRequest req, StreamObserver<CustomerParcelPageResponse> obs) {
            obs.onNext(CustomerParcelPageResponse.newBuilder()
                .addContent(CustomerParcelViewResponse.newBuilder()
                    .setTrackingRef("sub-" + req.getKeycloakSubject())
                    .build())
                .build());
            obs.onCompleted();
          }
        })
        .build()
        .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    ParcelPricingGrpcClient parcelGrpc = new ParcelPricingGrpcClient(channel, null);
    try {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(McpClientAutoConfiguration.class, McpToolCallbackAutoConfiguration.class))
          .withPropertyValues("spring.ai.mcp.client.enabled=false", "spring.ai.mcp.client.name=zentro-support",
              "spring.ai.mcp.client.request-timeout=8s")
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withBean(AssistantProperties.class, () -> new AssistantProperties("", "http://localhost", "deepseek-chat", 1000, 1000, 1000))
          .withBean(ParcelPricingGrpcClient.class, () -> parcelGrpc)
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
              assertThat(aliceResult.get(10, TimeUnit.SECONDS)).contains("sub-alice").doesNotContain("sub-bob");
              assertThat(bobResult.get(10, TimeUnit.SECONDS)).contains("sub-bob").doesNotContain("sub-alice");
            }
            assertThat(aliceContext.isActive()).isFalse();
            var subscription = (reactor.core.Disposable) ReflectionTestUtils.getField(aliceTransport, "subscription");
            assertThat(subscription.isDisposed()).isTrue();
          });
    } finally {
      channel.shutdownNow();
      grpcServer.shutdownNow();
    }
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
