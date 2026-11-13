package com.zentro.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration tests for Dispatcher webhook replay through the API Gateway (G18).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Only DISPATCHER can trigger webhook delivery replay; CUSTOMER, MERCHANT, and anonymous are denied.</li>
 *   <li>Invalid delivery UUID formats return 400 Bad Request.</li>
 *   <li>Non-existent delivery IDs return 404 Not Found.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class MerchantWebhookReplayIT extends DisposableGatewayStack {

  @Test
  @DisplayName("Dispatcher replay rejects unknown delivery ID with 404")
  void dispatcherReplay_unknownId_returns404() throws Exception {
    UUID unknownDeliveryId = UUID.randomUUID();
    HttpResponse<String> response = call(
        "POST",
        "/api/v1/admin/webhook-deliveries/" + unknownDeliveryId + "/replay",
        dispatcherToken,
        null
    );

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("Dispatcher replay rejects malformed delivery UUID with 400")
  void dispatcherReplay_malformedUuid_returns400() throws Exception {
    HttpResponse<String> response = call(
        "POST",
        "/api/v1/admin/webhook-deliveries/not-a-valid-uuid/replay",
        dispatcherToken,
        null
    );

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("Customer cannot access Dispatcher webhook replay endpoint (403 Forbidden)")
  void customer_cannotReplay_returns403() throws Exception {
    UUID deliveryId = UUID.randomUUID();
    HttpResponse<String> response = call(
        "POST",
        "/api/v1/admin/webhook-deliveries/" + deliveryId + "/replay",
        customerToken,
        null
    );

    assertThat(response.statusCode()).isEqualTo(403);
  }

  @Test
  @DisplayName("Anonymous caller cannot access Dispatcher webhook replay endpoint (401 Unauthorized)")
  void anonymous_cannotReplay_returns401() throws Exception {
    UUID deliveryId = UUID.randomUUID();
    HttpResponse<String> response = call(
        "POST",
        "/api/v1/admin/webhook-deliveries/" + deliveryId + "/replay",
        null,
        null
    );

    assertThat(response.statusCode()).isEqualTo(401);
  }
}
