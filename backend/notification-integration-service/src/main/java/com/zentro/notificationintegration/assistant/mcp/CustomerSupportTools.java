package com.zentro.notificationintegration.assistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zentro.contracts.parcel.v1.CustomerParcelPageResponse;
import com.zentro.contracts.parcel.v1.CustomerParcelViewResponse;
import com.zentro.contracts.parcel.v1.EstimateDeliveryFeeResponse;
import com.zentro.contracts.parcel.v1.ParcelHistoryResponse;
import com.zentro.contracts.parcel.v1.StatusHistoryEntryProto;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Only fixed, allowlisted gRPC operations are callable. Customer identity is captured from the
 * verified JWT outside model arguments.
 */
public final class CustomerSupportTools {
  private final ParcelPricingGrpcClient parcelGrpc;
  private final String customerSubject;
  private final ObjectMapper mapper;
  private final AtomicInteger calls = new AtomicInteger();
  private final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(70).toNanos();

  public CustomerSupportTools(ParcelPricingGrpcClient parcelGrpc, Jwt jwt, ObjectMapper mapper) {
    this.parcelGrpc = parcelGrpc;
    this.customerSubject = jwt != null && jwt.getSubject() != null ? jwt.getSubject() : "";
    this.mapper = mapper;
  }

  public List<SyncToolSpecification> specifications() {
    List<SyncToolSpecification> tools = new ArrayList<>();
    for (String name : List.of("get_parcel_details", "get_parcel_status", "get_parcel_information")) {
      tools.add(tool(name, "Read your parcel by parcel UUID. Customer ownership is enforced by the parcel gRPC API.",
          "{\"parcelId\":{\"type\":\"string\",\"format\":\"uuid\"}}", "\"parcelId\"", args -> {
            String id = text(args, "parcelId", 36);
            if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
            CustomerParcelViewResponse parcel = parcelGrpc.getCustomerParcelById(customerSubject, id);
            return parcelToNode(parcel);
          }));
    }
    for (String name : List.of("get_tracking_history", "get_current_tracking_status", "get_estimated_delivery")) {
      tools.add(tool(name, "Read tracking and estimated delivery for your own tracking reference.",
          "{\"trackingRef\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z0-9_-]{6,32}$\"}}", "\"trackingRef\"", args -> {
            String ref = text(args, "trackingRef", 32);
            if (!ref.matches("[A-Za-z0-9_-]{6,32}")) throw new IllegalArgumentException();
            CustomerParcelViewResponse parcel;
            try {
              parcel = parcelGrpc.getCustomerParcel(customerSubject, ref);
            } catch (StatusRuntimeException ex) {
              if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return mapper.createObjectNode().put("error", "Parcel not found in your deliveries.");
              }
              throw ex;
            }
            ParcelHistoryResponse history = parcelGrpc.getCustomerParcelHistory(customerSubject, ref);
            ObjectNode node = mapper.createObjectNode()
                .put("trackingRef", parcel.getTrackingRef())
                .put("status", parcel.getStatus())
                .put("estimatedDays", parcel.getEstimatedDays())
                .put("createdAt", parcel.getCreatedAt());
            ArrayNode events = mapper.createArrayNode();
            for (StatusHistoryEntryProto e : history.getEntriesList()) {
              events.add(mapper.createObjectNode()
                  .put("status", e.getStatus())
                  .put("reason", e.getReason())
                  .put("note", e.getNote())
                  .put("changedAt", e.getChangedAt()));
            }
            node.set("events", events);
            return node;
          }));
    }
    tools.add(tool("get_my_deliveries", "List your latest deliveries; no customer identifier is accepted.", "{}", "", args -> {
      CustomerParcelPageResponse mine = parcelGrpc.listCustomerParcels(customerSubject, 1, 10);
      ArrayNode result = mapper.createArrayNode();
      for (CustomerParcelViewResponse parcel : mine.getContentList()) {
        result.add(mapper.createObjectNode()
            .put("id", parcel.getId())
            .put("trackingRef", parcel.getTrackingRef())
            .put("status", parcel.getStatus())
            .put("estimatedDays", parcel.getEstimatedDays()));
      }
      return result;
    }));
    tools.add(tool("calculate_delivery_fee", "Get an actual price quote. Ask for pickup city and delivery city first; never guess them.",
        "{\"pickupCity\":{\"type\":\"string\"},\"deliveryCity\":{\"type\":\"string\"},\"weightKg\":{\"type\":\"number\",\"minimum\":0.01,\"maximum\":50}}",
        "\"pickupCity\",\"deliveryCity\",\"weightKg\"", args -> {
          String pickup = text(args, "pickupCity", 100), destination = text(args, "deliveryCity", 100);
          BigDecimal weight = new BigDecimal(String.valueOf(args.get("weightKg")));
          if (weight.compareTo(new BigDecimal("0.01")) < 0 || weight.compareTo(new BigDecimal("50")) > 0) {
            throw new IllegalArgumentException();
          }
          String destinationType = pickup.equalsIgnoreCase(destination) ? "SAME_CITY" : "OTHER_DISTRICT";
          EstimateDeliveryFeeResponse quote = parcelGrpc.estimateDeliveryFee(
              pickup, destination, weight.toPlainString(), destinationType);
          return mapper.createObjectNode()
              .put("fee", quote.getFee())
              .put("formattedFee", quote.getFormattedFee())
              .put("currency", quote.getCurrency())
              .put("estimatedDays", quote.getEstimatedDays());
        }));
    return tools;
  }

  private ObjectNode parcelToNode(CustomerParcelViewResponse parcel) {
    ObjectNode node = mapper.createObjectNode()
        .put("id", parcel.getId())
        .put("trackingRef", parcel.getTrackingRef())
        .put("status", parcel.getStatus())
        .put("fee", parcel.getFee())
        .put("currency", parcel.getCurrency())
        .put("estimatedDays", parcel.getEstimatedDays());
    if (parcel.hasParcel()) {
      node.set("parcel", mapper.createObjectNode()
          .put("weightKg", parcel.getParcel().getWeightKg())
          .put("category", parcel.getParcel().getCategory())
          .put("size", parcel.getParcel().getSize())
          .put("description", parcel.getParcel().getDescription()));
    }
    return node;
  }

  private SyncToolSpecification tool(String name, String description, String properties, String required,
      java.util.function.Function<Map<String, Object>, JsonNode> action) {
    String schema = "{\"type\":\"object\",\"properties\":" + properties + ",\"required\":[" + required + "],\"additionalProperties\":false}";
    McpSchema.JsonSchema inputSchema;
    try {
      inputSchema = mapper.readValue(schema, McpSchema.JsonSchema.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Invalid tool schema", ex);
    }
    Set<String> allowed = Set.copyOf(inputSchema.properties().keySet());
    var tool = new McpSchema.Tool(name, null, description, inputSchema, null, null, null);
    return new SyncToolSpecification(tool, (exchange, args) -> {
      if (calls.incrementAndGet() > 6 || System.nanoTime() > deadline) return new McpSchema.CallToolResult("Tool budget exceeded. Ask a narrower question.", true);
      try {
        if (args == null || !allowed.containsAll(args.keySet())) throw new IllegalArgumentException();
        JsonNode result = action.apply(args);
        return new McpSchema.CallToolResult(mapper.writeValueAsString(result), false);
      } catch (IllegalArgumentException ex) {
        return new McpSchema.CallToolResult("Invalid tool arguments. Ask the customer for valid values.", true);
      } catch (StatusRuntimeException ex) {
        return new McpSchema.CallToolResult("Authorized delivery information is unavailable. Do not infer a result.", true);
      } catch (Exception ex) {
        return new McpSchema.CallToolResult("Tool unavailable. Please try again later.", true);
      }
    });
  }

  private static String text(Map<String, Object> args, String key, int max) {
    if (!(args.get(key) instanceof String value) || value.isBlank() || value.length() > max) throw new IllegalArgumentException();
    return value.trim();
  }
}
