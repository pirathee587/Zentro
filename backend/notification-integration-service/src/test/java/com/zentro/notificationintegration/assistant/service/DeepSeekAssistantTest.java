package com.zentro.notificationintegration.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zentro.contracts.parcel.v1.CustomerParcelPageResponse;
import com.zentro.contracts.parcel.v1.CustomerParcelReferenceRequest;
import com.zentro.contracts.parcel.v1.CustomerParcelViewResponse;
import com.zentro.contracts.parcel.v1.ListCustomerParcelsRequest;
import com.zentro.contracts.parcel.v1.ParcelPricingServiceGrpc;
import com.zentro.notificationintegration.assistant.mcp.CustomerMcpClientFactory;
import com.zentro.notificationintegration.assistant.mcp.CustomerSupportTools;
import com.zentro.notificationintegration.assistant.mcp.ParcelPricingGrpcClient;
import com.zentro.notificationintegration.config.AssistantProperties;
import io.modelcontextprotocol.spec.McpSchema;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;

class DeepSeekAssistantTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private Server grpcServer;
  private ManagedChannel grpcChannel;

  @AfterEach
  void tearDown() {
    if (grpcChannel != null) grpcChannel.shutdownNow();
    if (grpcServer != null) grpcServer.shutdownNow();
  }

  private ParcelPricingGrpcClient grpcClient(ParcelPricingServiceGrpc.ParcelPricingServiceImplBase impl)
      throws Exception {
    String name = InProcessServerBuilder.generateName();
    grpcServer = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
    grpcChannel = InProcessChannelBuilder.forName(name).directExecutor().build();
    return new ParcelPricingGrpcClient(grpcChannel, null);
  }

  private DeepSeekAssistant assistant(AssistantProperties properties, ParcelPricingGrpcClient parcelGrpc) {
    return new DeepSeekAssistant(properties,
        new CustomerMcpClientFactory(properties, mapper, new StandardEnvironment(), parcelGrpc));
  }

  private Jwt token(String subject) {
    return Jwt.withTokenValue("private-token-" + subject).header("alg", "RS256").subject(subject).build();
  }

  @Test
  void deepSeekToolCallsTraverseRealMcpAndCallParcelGrpcWithCustomerSubject() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger modelCalls = new AtomicInteger(), grpcCalls = new AtomicInteger();
    List<String> failures = Collections.synchronizedList(new ArrayList<>());
    server.createContext("/chat/completions", exchange -> {
      try {
        var body = mapper.readTree(exchange.getRequestBody());
        if (!"Bearer provider-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          failures.add("provider auth");
        }
        if (body.toString().contains("private-token")) {
          failures.add("JWT leaked to provider");
        }
        int call = modelCalls.incrementAndGet();
        if (call == 2 && !body.toString().contains("IN_TRANSIT")) {
          failures.add("missing tool result");
        }
        String toolName = "";
        for (var tool : body.path("tools")) {
          String candidate = tool.path("function").path("name").asText();
          if (candidate.endsWith("get_my_deliveries")) toolName = candidate;
        }
        String message = call == 1 ? """
            {"role":"assistant","content":"","tool_calls":[{"id":"call-1","type":"function","function":{"name":"%s","arguments":"{}"}}]}
            """.formatted(toolName) : "{\"role\":\"assistant\",\"content\":\"Your parcel is in transit.\"}";
        byte[] response = ("{\"id\":\"completion-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":"
            + message + ",\"finish_reason\":\"" + (call == 1 ? "tool_calls" : "stop")
            + "\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
      } finally {
        exchange.close();
      }
    });
    server.start();
    var parcelGrpc = grpcClient(new ParcelPricingServiceGrpc.ParcelPricingServiceImplBase() {
      @Override
      public void listCustomerParcels(
          ListCustomerParcelsRequest req, StreamObserver<CustomerParcelPageResponse> obs) {
        if (!"alice".equals(req.getKeycloakSubject())) failures.add("wrong subject");
        grpcCalls.incrementAndGet();
        obs.onNext(CustomerParcelPageResponse.newBuilder()
            .addContent(CustomerParcelViewResponse.newBuilder()
                .setId("p1")
                .setTrackingRef("ZTR-123456")
                .setStatus("IN_TRANSIT")
                .build())
            .build());
        obs.onCompleted();
      }
    });
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var assistant = assistant(
          new AssistantProperties("provider-secret", url, "deepseek-chat", 1000, 2000, 1000),
          parcelGrpc);
      assertThat(assistant.reply(token("alice"), List.of(), "Where is my parcel?"))
          .isEqualTo("Your parcel is in transit.");
      assertThat(modelCalls).hasValue(2);
      assertThat(grpcCalls).hasValue(1);
      assertThat(failures).isEmpty();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void absentKeyDoesNotRequireProviderOrMcpConnectivity() {
    var assistant = assistant(
        new AssistantProperties("", "https://api.deepseek.com", "deepseek-chat", 1000, 1000, 1000),
        null);
    assertThat(assistant.reply(token("alice"), List.of(), "hello")).contains("not configured");
  }

  @Test
  void invalidArgumentsAndUnknownIdentityFieldsNeverReachGrpc() {
    var tools = new CustomerSupportTools(null, token("alice"), mapper);
    var spec = tools.specifications().stream()
        .filter(t -> t.tool().name().equals("get_parcel_details"))
        .findFirst().orElseThrow();
    var result = spec.callHandler().apply(null, new McpSchema.CallToolRequest(spec.tool().name(), Map.of("parcelId", "../../admin/users", "customerId", "bob")));
    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("Invalid tool arguments");
  }

  @Test
  void providerAuthenticationRateLimitAndServerErrorsReturnSafeFallbacksWithoutRetries() throws Exception {
    for (int code : List.of(401, 429, 503)) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      AtomicInteger requests = new AtomicInteger();
      server.createContext("/chat/completions", exchange -> {
        requests.incrementAndGet();
        exchange.sendResponseHeaders(code, 0);
        exchange.getResponseBody().write("private provider body".getBytes(StandardCharsets.UTF_8));
        exchange.close();
      });
      server.start();
      try {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var assistant = assistant(
            new AssistantProperties("provider-secret", url, "deepseek-chat", 100, 500, 100),
            null);
        String answer = assistant.reply(token("alice"), List.of(), "hello");
        assertThat(answer).doesNotContain("private provider body", "provider-secret", "private-token");
        assertThat(answer).contains(code == 401 ? "authenticate" : code == 429 ? "busy" : "temporarily unavailable");
        assertThat(requests).hasValue(1);
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void foreignTrackingReferenceNeverReturnsHistory() throws Exception {
    AtomicInteger historyReads = new AtomicInteger();
    var parcelGrpc = grpcClient(new ParcelPricingServiceGrpc.ParcelPricingServiceImplBase() {
      @Override
      public void getCustomerParcel(
          CustomerParcelReferenceRequest req, StreamObserver<CustomerParcelViewResponse> obs) {
        obs.onError(Status.NOT_FOUND.withDescription("not found").asRuntimeException());
      }

      @Override
      public void getCustomerParcelHistory(
          CustomerParcelReferenceRequest req, StreamObserver<com.zentro.contracts.parcel.v1.ParcelHistoryResponse> obs) {
        historyReads.incrementAndGet();
        obs.onError(Status.INTERNAL.asRuntimeException());
      }
    });
    var tools = new CustomerSupportTools(parcelGrpc, token("alice"), mapper);
    var spec = tools.specifications().stream()
        .filter(t -> t.tool().name().equals("get_tracking_history"))
        .findFirst().orElseThrow();
    var result = spec.callHandler().apply(null, new McpSchema.CallToolRequest(spec.tool().name(), Map.of("trackingRef", "ZTR-FOREIGN")));
    assertThat(result.content().toString()).contains("not found in your deliveries");
    assertThat(historyReads).hasValue(0);
  }
}
