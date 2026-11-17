package com.zentro.notificationintegration.assistant.mcp;

import com.zentro.notificationintegration.config.AssistantProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Only fixed, allowlisted gateway paths are callable. Identity is captured outside model arguments. */
public final class CustomerSupportTools {
  private final RestClient gateway;
  private final ObjectMapper mapper;
  private final AtomicInteger calls = new AtomicInteger();
  private final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(70).toNanos();

  public CustomerSupportTools(AssistantProperties properties, Jwt jwt, ObjectMapper mapper) {
    this.mapper = mapper;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.connectTimeoutMs());
    factory.setReadTimeout(properties.toolTimeoutMs());
    gateway = RestClient.builder().baseUrl(properties.gatewayUrl()).requestFactory(factory)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue()).build();
  }

  public List<SyncToolSpecification> specifications() {
    List<SyncToolSpecification> tools = new ArrayList<>();
    for (String name : List.of("get_parcel_details", "get_parcel_status", "get_parcel_information")) {
      tools.add(tool(name, "Read your parcel by parcel UUID. Customer ownership is enforced by the parcel API.",
          "{\"parcelId\":{\"type\":\"string\",\"format\":\"uuid\"}}", "\"parcelId\"", args -> {
            String id = text(args, "parcelId", 36);
            if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
            return select(get("/api/v1/parcels/" + id), "id", "trackingRef", "status", "fee", "currency", "estimatedDays", "parcel");
          }));
    }
    for (String name : List.of("get_tracking_history", "get_current_tracking_status", "get_estimated_delivery")) {
      tools.add(tool(name, "Read tracking and estimated delivery for your own tracking reference.",
          "{\"trackingRef\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z0-9_-]{6,32}$\"}}", "\"trackingRef\"", args -> {
            String ref = text(args, "trackingRef", 32);
            if (!ref.matches("[A-Za-z0-9_-]{6,32}")) throw new IllegalArgumentException();
            JsonNode mine = gateway.get().uri(b -> b.path("/api/v1/tracking/mine")
                .queryParam("q", ref).queryParam("size", 100).build()).retrieve().body(JsonNode.class);
            boolean owned = false;
            if (mine != null) for (JsonNode parcel : mine.path("content")) {
              if (ref.equals(parcel.path("trackingRef").asText())) owned = true;
            }
            if (!owned) return mapper.createObjectNode().put("error", "Parcel not found in your deliveries.");
            return get("/api/v1/tracking/" + ref);
          }));
    }
    tools.add(tool("get_my_deliveries", "List your latest deliveries; no customer identifier is accepted.", "{}", "", args -> {
      JsonNode mine = get("/api/v1/tracking/mine?size=10");
      var result = mapper.createArrayNode();
      for (JsonNode parcel : mine.path("content")) result.add(select(parcel, "id", "trackingRef", "status", "estimatedDays"));
      return result;
    }));
    tools.add(tool("calculate_delivery_fee", "Get an actual price quote. Ask for pickup city and delivery city first; never guess them.",
        "{\"pickupCity\":{\"type\":\"string\"},\"deliveryCity\":{\"type\":\"string\"},\"weightKg\":{\"type\":\"number\",\"minimum\":0.01,\"maximum\":50}}",
        "\"pickupCity\",\"deliveryCity\",\"weightKg\"", args -> {
          String pickup = text(args, "pickupCity", 100), destination = text(args, "deliveryCity", 100);
          BigDecimal weight = new BigDecimal(String.valueOf(args.get("weightKg")));
          if (weight.compareTo(new BigDecimal("0.01")) < 0 || weight.compareTo(new BigDecimal("50")) > 0) throw new IllegalArgumentException();
          return gateway.post().uri("/api/v1/parcels/estimate").body(Map.of("pickupCity", pickup,
              "deliveryCity", destination, "weightKg", weight)).retrieve().body(JsonNode.class);
        }));
    return tools;
  }

  private SyncToolSpecification tool(String name, String description, String properties, String required,
      java.util.function.Function<Map<String, Object>, JsonNode> action) {
    String schema = "{\"type\":\"object\",\"properties\":" + properties + ",\"required\":[" + required + "],\"additionalProperties\":false}";
    Set<String> allowed;
    try { allowed = mapper.readTree(properties).properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()); }
    catch (Exception ex) { throw new IllegalStateException("Invalid tool schema"); }
    return new SyncToolSpecification(new McpSchema.Tool(name, description, schema), (exchange, args) -> {
      if (calls.incrementAndGet() > 6 || System.nanoTime() > deadline) return new McpSchema.CallToolResult("Tool budget exceeded. Ask a narrower question.", true);
      try {
        if (args == null || !allowed.containsAll(args.keySet())) throw new IllegalArgumentException();
        JsonNode result = action.apply(args);
        return new McpSchema.CallToolResult(mapper.writeValueAsString(result), false);
      } catch (IllegalArgumentException ex) {
        return new McpSchema.CallToolResult("Invalid tool arguments. Ask the customer for valid values.", true);
      } catch (RestClientException ex) {
        return new McpSchema.CallToolResult("Authorized delivery information is unavailable. Do not infer a result.", true);
      } catch (Exception ex) {
        return new McpSchema.CallToolResult("Tool unavailable. Please try again later.", true);
      }
    });
  }

  private JsonNode get(String path) { return gateway.get().uri(path).retrieve().body(JsonNode.class); }
  private ObjectNode select(JsonNode source, String... fields) {
    ObjectNode result = mapper.createObjectNode();
    if (source != null) for (String field : fields) if (source.has(field)) result.set(field, source.get(field));
    return result;
  }
  private static String text(Map<String, Object> args, String key, int max) {
    if (!(args.get(key) instanceof String value) || value.isBlank() || value.length() > max) throw new IllegalArgumentException();
    return value.trim();
  }
}
