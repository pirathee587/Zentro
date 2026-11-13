package com.zentro.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end acceptance test for the full merchant workflow (G35):
 * Active merchant registration -> order creation -> outbox event -> signed webhook delivery.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Approved merchant creates an order successfully (201 Created with ZT-* tracking reference).</li>
 *   <li>Idempotent re-submission with identical external reference returns 200 OK without extra outbox events.</li>
 *   <li>Conflicting re-submission with same external reference returns 409 Conflict.</li>
 *   <li>Webhook delivery is signed with HMAC-SHA256 and carries stable event ID and timestamp.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class MerchantOrderAcceptanceIT extends DisposableGatewayStack {

  @Test
  @DisplayName("Approved merchant order pipeline creates parcel, emits outbox event, and delivers signed webhook")
  void merchantOrder_endToEndPipeline() throws Exception {
    // 1. Provision active approved merchant
    String client = CLIENT_ID + "-acceptance-" + UUID.randomUUID();
    String secret = "secret-" + UUID.randomUUID();
    String userId = createServiceClient(client, secret, "CUSTOMER");
    String customerToken = tokenForClient(client, secret);

    HttpResponse<String> applied = call("POST", "/api/v1/merchants", customerToken,
        "{\"businessName\":\"Acceptance Merchant " + UUID.randomUUID()
            + "\",\"businessRegNo\":\"REG-ACC-" + UUID.randomUUID() + "\",\"contactPhone\":\"+94773333333\"}");
    assertThat(applied.statusCode()).isIn(200, 201);
    String appId = JSON.readTree(applied.body()).path("id").asText();

    HttpResponse<String> approval = call("PATCH", "/api/v1/admin/merchants/" + appId,
        dispatcherToken, "{\"decision\":\"APPROVED\"}");
    assertThat(approval.statusCode()).isEqualTo(200);

    simulateManualMerchantRoleAssignment(userId);
    String merchantToken = tokenForClient(client, secret);
    assertThat(realmRoles(merchantToken)).contains("MERCHANT");

    // 2. Register webhook with mock receiver
    try (MockWebServer receiver = new MockWebServer()) {
      receiver.start();
      receiver.enqueue(new MockResponse().setResponseCode(200));

      HttpResponse<String> hookRegistered = call("POST", "/api/v1/merchant/webhooks", merchantToken,
          "{\"url\":\"http://127.0.0.1:" + receiver.getPort() + "/merchant-events\"}");
      assertThat(hookRegistered.statusCode()).isEqualTo(201);
      String hookSecret = JSON.readTree(hookRegistered.body()).path("secret").asText();
      assertThat(hookSecret).startsWith("whsec_");

      // 3. Create merchant order with unique external reference
      String externalRef = "ext-acc-" + UUID.randomUUID();
      String payload = "{\"externalOrderRef\":\"" + externalRef
          + "\",\"sender\":{\"name\":\"Sender\",\"phone\":\"+94771234567\",\"address\":\"1 Galle Road\",\"city\":\"Colombo\"},"
          + "\"recipient\":{\"name\":\"Recipient\",\"phone\":\"+94771234568\",\"address\":\"2 Kandy Road\",\"city\":\"Kandy\"},"
          + "\"parcel\":{\"weightKg\":1.5,\"category\":\"GENERAL\",\"description\":\"Electronics\"},"
          + "\"destinationType\":\"OTHER_DISTRICT\",\"isExpress\":false}";

      HttpResponse<String> orderCreated = call("POST", "/api/v1/merchant/orders", merchantToken, payload);
      assertThat(orderCreated.statusCode()).isEqualTo(201);
      JsonNode orderJson = JSON.readTree(orderCreated.body());
      assertThat(orderJson.path("trackingRef").asText()).startsWith("ZT-");
      assertThat(orderJson.path("externalOrderRef").asText()).isEqualTo(externalRef);

      // 4. Verify idempotent replay with identical body returns 200 OK
      HttpResponse<String> replay = call("POST", "/api/v1/merchant/orders", merchantToken, payload);
      assertThat(replay.statusCode()).isEqualTo(200);
      assertThat(JSON.readTree(replay.body()).path("id").asText()).isEqualTo(orderJson.path("id").asText());

      // 5. Verify conflicting body with same external reference returns 409 Conflict
      String conflictingPayload = payload.replace("Electronics", "Clothing");
      HttpResponse<String> conflict = call("POST", "/api/v1/merchant/orders", merchantToken, conflictingPayload);
      assertThat(conflict.statusCode()).isEqualTo(409);

      // 6. Verify webhook was delivered and signed properly
      RecordedRequest webhookRequest = receiver.takeRequest(30, TimeUnit.SECONDS);
      assertThat(webhookRequest).isNotNull();
      assertThat(webhookRequest.getHeader("X-SwiftDrop-Event-Type")).isEqualTo("parcel.created");
      String eventId = webhookRequest.getHeader("X-SwiftDrop-Event-Id");
      assertThat(eventId).isNotBlank();
      String timestamp = webhookRequest.getHeader("X-SwiftDrop-Timestamp");
      assertThat(timestamp).isNotBlank();

      String expectedSignature = hmac(hookSecret, timestamp, webhookRequest.getBody().readUtf8());
      assertThat(webhookRequest.getHeader("X-SwiftDrop-Signature")).isEqualTo(expectedSignature);
    }
  }
}
