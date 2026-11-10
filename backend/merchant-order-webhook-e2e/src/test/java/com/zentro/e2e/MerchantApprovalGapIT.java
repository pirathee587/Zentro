package com.zentro.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cross-service known-gap coverage. All business HTTP calls go through the real API gateway.
 * Keycloak's admin API is used only to seed fixture users and simulate the documented manual role grant.
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class MerchantApprovalGapIT extends DisposableGatewayStack {
  @Test
  void findingK4_approvalDoesNotGrantMerchantRoleUntilManualKeycloakAssignment() throws Exception {
    String applyBody = "{\"businessName\":\"E2E " + UUID.randomUUID()
        + "\",\"businessRegNo\":\"E2E-" + UUID.randomUUID() + "\",\"contactPhone\":\"+94771234567\"}";
    HttpResponse<String> applied = call("POST", "/api/v1/merchants", customerToken, applyBody);
    assertThat(applied.statusCode()).isIn(200, 201);
    JsonNode application = JSON.readTree(applied.body());
    String applicationId = application.path("id").asText();
    assertThat(application.path("status").asText()).isEqualTo("PENDING");

    HttpResponse<String> approval = call("PATCH", "/api/v1/admin/merchants/" + applicationId,
        dispatcherToken, "{\"decision\":\"APPROVED\"}");
    assertThat(approval.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(approval.body()).path("status").asText()).isEqualTo("APPROVED");

    String freshBeforeAssignment = tokenForClient(CUSTOMER_CLIENT, CUSTOMER_SECRET);
    assertThat(realmRoles(freshBeforeAssignment)).doesNotContain("MERCHANT");
    assertThat(call("POST", "/api/v1/merchant/orders", freshBeforeAssignment, orderBody()).statusCode()).isEqualTo(403);
    assertThat(call("POST", "/api/v1/merchant/webhooks", freshBeforeAssignment,
        "{\"url\":\"https://example.org/hooks\"}").statusCode()).isEqualTo(403);

    simulateManualMerchantRoleAssignment(customerId);
    assertThat(realmRoles(freshBeforeAssignment)).doesNotContain("MERCHANT");
    String afterReLogin = tokenForClient(CUSTOMER_CLIENT, CUSTOMER_SECRET);
    assertThat(realmRoles(afterReLogin)).contains("MERCHANT");
    try (MockWebServer receiver = new MockWebServer()) {
      receiver.start();
      receiver.enqueue(new MockResponse().setResponseCode(503));
      receiver.enqueue(new MockResponse().setResponseCode(200));
      HttpResponse<String> registered = call("POST", "/api/v1/merchant/webhooks", afterReLogin,
          "{\"url\":\"http://127.0.0.1:" + receiver.getPort() + "/events\"}");
      assertThat(registered.statusCode()).isEqualTo(201);
      JsonNode secretResponse = JSON.readTree(registered.body());
      String secret = secretResponse.path("secret").asText();
      assertThat(secret).startsWith("whsec_");
      HttpResponse<String> subscription = call("GET", "/api/v1/merchant/webhooks", afterReLogin, null);
      assertThat(subscription.statusCode()).isEqualTo(200);
      assertThat(JSON.readTree(subscription.body()).has("secret")).isFalse();

      String submittedOrder = orderBody();
      HttpResponse<String> createdOrder = call("POST", "/api/v1/merchant/orders", afterReLogin, submittedOrder);
      assertThat(createdOrder.statusCode()).isEqualTo(201);
      JsonNode order = JSON.readTree(createdOrder.body());
      assertThat(order.path("trackingRef").asText()).startsWith("ZT-");

      RecordedRequest firstAttempt = receiver.takeRequest(30, TimeUnit.SECONDS);
      RecordedRequest secondAttempt = receiver.takeRequest(30, TimeUnit.SECONDS);
      assertThat(firstAttempt).isNotNull();
      assertThat(secondAttempt).isNotNull();
      assertThat(firstAttempt.getHeader("X-SwiftDrop-Event-Type")).isEqualTo("parcel.created");
      assertThat(firstAttempt.getHeader("X-SwiftDrop-Event-Id")).isEqualTo(secondAttempt.getHeader("X-SwiftDrop-Event-Id"));
      assertThat(firstAttempt.getHeader("X-SwiftDrop-Signature")).isEqualTo(
          hmac(secret, firstAttempt.getHeader("X-SwiftDrop-Timestamp"), firstAttempt.getBody().readUtf8()));
      assertThat(secondAttempt.getHeader("X-SwiftDrop-Signature")).isEqualTo(
          hmac(secret, secondAttempt.getHeader("X-SwiftDrop-Timestamp"), secondAttempt.getBody().readUtf8()));
      assertThat(firstAttempt.getHeader("X-Delivery-Id")).isNull(); // K5: current wire contract has no delivery-id header.
    }
  }

  @Test
  void rejectedMerchant_remainsBlockedAfterManualRoleAssignment_noParcelIsCreated() throws Exception {
    String client = CLIENT_ID + "-rejected-" + UUID.randomUUID();
    String secret = "rejected-" + UUID.randomUUID();
    String rejectedUserId = createServiceClient(client, secret, "CUSTOMER");
    String customer = tokenForClient(client, secret);
    HttpResponse<String> applied = call("POST", "/api/v1/merchants", customer,
        "{\"businessName\":\"Rejected E2E\",\"businessRegNo\":\"REJECTED-" + UUID.randomUUID()
            + "\",\"contactPhone\":\"+94771234567\"}");
    assertThat(applied.statusCode()).isIn(200, 201);
    String applicationId = JSON.readTree(applied.body()).path("id").asText();
    HttpResponse<String> rejected = call("PATCH", "/api/v1/admin/merchants/" + applicationId,
        dispatcherToken, "{\"decision\":\"REJECTED\",\"rejectionReason\":\"E2E rejection\"}");
    assertThat(rejected.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(rejected.body()).path("status").asText()).isEqualTo("REJECTED");

    // Even a manual Keycloak role mapping cannot bypass S1's approval status check in S2.
    simulateManualMerchantRoleAssignment(rejectedUserId);
    String roleBearingToken = tokenForClient(client, secret);
    assertThat(realmRoles(roleBearingToken)).contains("MERCHANT");
    assertThat(call("POST", "/api/v1/merchant/orders", roleBearingToken, orderBody()).statusCode()).isEqualTo(403);
  }

}
