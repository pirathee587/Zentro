package com.zentro.notificationintegration.assistant.service;

import com.zentro.notificationintegration.config.AssistantProperties;
import com.zentro.notificationintegration.assistant.mcp.CustomerSupportTools;
import com.zentro.notificationintegration.assistant.mcp.CustomerMcpClientFactory;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class DeepSeekAssistantTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private DeepSeekAssistant assistant(AssistantProperties properties) {
    return new DeepSeekAssistant(properties, new CustomerMcpClientFactory(properties, mapper, new StandardEnvironment()));
  }
  private Jwt token(String subject) { return Jwt.withTokenValue("private-token-" + subject).header("alg","RS256").subject(subject).build(); }

  @Test void deepSeekToolCallsTraverseRealMcpAndForwardOnlyCustomerJwtToGateway() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger modelCalls = new AtomicInteger(), gatewayCalls = new AtomicInteger();
    List<String> failures = Collections.synchronizedList(new ArrayList<>());
    server.createContext("/chat/completions", exchange -> {
      try {
        var body = mapper.readTree(exchange.getRequestBody());
        if (!"Bearer provider-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) failures.add("provider auth");
        if (body.toString().contains("private-token")) failures.add("JWT leaked to provider");
        int call = modelCalls.incrementAndGet();
        if (call == 2 && !body.toString().contains("IN_TRANSIT")) failures.add("missing tool result");
        String toolName = "";
        for (var tool : body.path("tools")) {
          String candidate = tool.path("function").path("name").asText();
          if (candidate.endsWith("get_my_deliveries")) toolName = candidate;
        }
        String message = call == 1 ? """
            {"role":"assistant","content":"","tool_calls":[{"id":"call-1","type":"function","function":{"name":"%s","arguments":"{}"}}]}
            """.formatted(toolName) : "{\"role\":\"assistant\",\"content\":\"Your parcel is in transit.\"}";
        byte[] response = ("{\"id\":\"completion-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + (call == 1 ? "tool_calls" : "stop") + "\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
      } finally { exchange.close(); }
    });
    server.createContext("/api/v1/tracking/mine", exchange -> {
      if (!"Bearer private-token-alice".equals(exchange.getRequestHeaders().getFirst("Authorization"))) failures.add("gateway auth");
      gatewayCalls.incrementAndGet();
      byte[] response = "{\"content\":[{\"id\":\"p1\",\"trackingRef\":\"ZTR-123456\",\"status\":\"IN_TRANSIT\"}]}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var assistant = assistant(new AssistantProperties("provider-secret", url, "deepseek-chat", url, 1000, 2000, 1000));
      assertThat(assistant.reply(token("alice"), List.of(), "Where is my parcel?")).isEqualTo("Your parcel is in transit.");
      assertThat(modelCalls).hasValue(2); assertThat(gatewayCalls).hasValue(1); assertThat(failures).isEmpty();
    } finally { server.stop(0); }
  }

  @Test void absentKeyDoesNotRequireProviderOrMcpConnectivity() {
    var assistant = assistant(new AssistantProperties("", "https://api.deepseek.com", "deepseek-chat", "http://localhost:8080", 1000, 1000, 1000));
    assertThat(assistant.reply(token("alice"), List.of(), "hello")).contains("not configured");
  }

  @Test void invalidArgumentsAndUnknownIdentityFieldsNeverReachGateway() {
    var tools = new CustomerSupportTools(new AssistantProperties("", "", "", "http://127.0.0.1:1", 100, 100, 100), token("alice"), mapper);
    var spec = tools.specifications().stream().filter(t -> t.tool().name().equals("get_parcel_details")).findFirst().orElseThrow();
    var result = spec.call().apply(null, Map.of("parcelId", "../../admin/users", "customerId", "bob"));
    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("Invalid tool arguments");
  }
  @Test void providerAuthenticationRateLimitAndServerErrorsReturnSafeFallbacksWithoutRetries() throws Exception {
    for (int code : List.of(401,429,503)) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      AtomicInteger requests = new AtomicInteger();
      server.createContext("/chat/completions", exchange -> {
        requests.incrementAndGet(); exchange.sendResponseHeaders(code,0);
        exchange.getResponseBody().write("private provider body".getBytes(StandardCharsets.UTF_8)); exchange.close();
      });
      server.start();
      try {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var assistant = assistant(new AssistantProperties("provider-secret",url,"deepseek-chat",url,100,500,100));
        String answer = assistant.reply(token("alice"),List.of(),"hello");
        assertThat(answer).doesNotContain("private provider body","provider-secret","private-token");
        assertThat(answer).contains(code == 401 ? "authenticate" : code == 429 ? "busy" : "temporarily unavailable");
        assertThat(requests).hasValue(1);
      } finally { server.stop(0); }
    }
  }

  @Test void foreignTrackingReferenceNeverReachesPublicLookup() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    AtomicInteger publicReads = new AtomicInteger();
    server.createContext("/api/v1/tracking/mine",exchange -> {
      byte[] data = "{\"content\":[]}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type","application/json");
      exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data); exchange.close();
    });
    server.createContext("/api/v1/tracking/ZTR-FOREIGN",exchange -> { publicReads.incrementAndGet(); exchange.sendResponseHeaders(500,-1); exchange.close(); });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var tools = new CustomerSupportTools(new AssistantProperties("","","",url,100,500,500),token("alice"),mapper);
      var spec = tools.specifications().stream().filter(t -> t.tool().name().equals("get_tracking_history")).findFirst().orElseThrow();
      var result = spec.call().apply(null,Map.of("trackingRef","ZTR-FOREIGN"));
      assertThat(result.content().toString()).contains("not found in your deliveries");
      assertThat(publicReads).hasValue(0);
    } finally { server.stop(0); }
  }

}
