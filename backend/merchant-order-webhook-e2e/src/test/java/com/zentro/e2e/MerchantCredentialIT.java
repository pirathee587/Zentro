package com.zentro.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration tests for Merchant credentials, role enforcement, and cross-merchant isolation (G19).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Bearer JWT is required; unauthenticated calls return 401.</li>
 *   <li>Non-MERCHANT roles (CUSTOMER, DISPATCHER) return 403.</li>
 *   <li>Approved active merchant credentials succeed.</li>
 *   <li>Cross-merchant isolation: a merchant cannot read another merchant's order (returns 404).</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class MerchantCredentialIT extends DisposableGatewayStack {

  @Test
  @DisplayName("Unauthenticated request to merchant orders returns 401 Unauthorized")
  void unauthenticated_merchantOrders_returns401() throws Exception {
    HttpResponse<String> response = call("POST", "/api/v1/merchant/orders", null, orderBody());
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("Customer token lacks MERCHANT role and is denied with 403 Forbidden")
  void customerToken_merchantOrders_returns403() throws Exception {
    HttpResponse<String> response = call("POST", "/api/v1/merchant/orders", customerToken, orderBody());
    assertThat(response.statusCode()).isEqualTo(403);
  }

  @Test
  @DisplayName("Dispatcher token lacks MERCHANT role and is denied with 403 Forbidden")
  void dispatcherToken_merchantOrders_returns403() throws Exception {
    HttpResponse<String> response = call("POST", "/api/v1/merchant/orders", dispatcherToken, orderBody());
    assertThat(response.statusCode()).isEqualTo(403);
  }

  @Test
  @DisplayName("Unauthenticated request to merchant webhooks returns 401 Unauthorized")
  void unauthenticated_merchantWebhooks_returns401() throws Exception {
    HttpResponse<String> response = call("GET", "/api/v1/merchant/webhooks", null, null);
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("Approved merchant with MERCHANT role can create order and cross-merchant access returns 404")
  void approvedMerchant_orderCreationAndCrossMerchantIsolation() throws Exception {
    // 1. Register Merchant A
    String clientA = CLIENT_ID + "-merch-a-" + UUID.randomUUID();
    String secretA = "secret-a-" + UUID.randomUUID();
    String userAId = createServiceClient(clientA, secretA, "CUSTOMER");
    String tokenA = tokenForClient(clientA, secretA);

    HttpResponse<String> appliedA = call("POST", "/api/v1/merchants", tokenA,
        "{\"businessName\":\"Merchant A " + UUID.randomUUID()
            + "\",\"businessRegNo\":\"REG-A-" + UUID.randomUUID() + "\",\"contactPhone\":\"+94771111111\"}");
    assertThat(appliedA.statusCode()).isIn(200, 201);
    String appAId = JSON.readTree(appliedA.body()).path("id").asText();

    HttpResponse<String> approvalA = call("PATCH", "/api/v1/admin/merchants/" + appAId,
        dispatcherToken, "{\"decision\":\"APPROVED\"}");
    assertThat(approvalA.statusCode()).isEqualTo(200);

    simulateManualMerchantRoleAssignment(userAId);
    String merchantAToken = tokenForClient(clientA, secretA);
    assertThat(realmRoles(merchantAToken)).contains("MERCHANT");

    // 2. Register Merchant B
    String clientB = CLIENT_ID + "-merch-b-" + UUID.randomUUID();
    String secretB = "secret-b-" + UUID.randomUUID();
    String userBId = createServiceClient(clientB, secretB, "CUSTOMER");
    String tokenB = tokenForClient(clientB, secretB);

    HttpResponse<String> appliedB = call("POST", "/api/v1/merchants", tokenB,
        "{\"businessName\":\"Merchant B " + UUID.randomUUID()
            + "\",\"businessRegNo\":\"REG-B-" + UUID.randomUUID() + "\",\"contactPhone\":\"+94772222222\"}");
    assertThat(appliedB.statusCode()).isIn(200, 201);
    String appBId = JSON.readTree(appliedB.body()).path("id").asText();

    HttpResponse<String> approvalB = call("PATCH", "/api/v1/admin/merchants/" + appBId,
        dispatcherToken, "{\"decision\":\"APPROVED\"}");
    assertThat(approvalB.statusCode()).isEqualTo(200);

    simulateManualMerchantRoleAssignment(userBId);
    String merchantBToken = tokenForClient(clientB, secretB);
    assertThat(realmRoles(merchantBToken)).contains("MERCHANT");

    // 3. Merchant A creates an order
    HttpResponse<String> orderCreatedA = call("POST", "/api/v1/merchant/orders", merchantAToken, orderBody());
    assertThat(orderCreatedA.statusCode()).isEqualTo(201);
    JsonNode orderAJson = JSON.readTree(orderCreatedA.body());
    String orderAId = orderAJson.path("id").asText();
    assertThat(orderAId).isNotBlank();

    // 4. Merchant A can read their own order
    HttpResponse<String> readByA = call("GET", "/api/v1/merchant/orders/" + orderAId, merchantAToken, null);
    assertThat(readByA.statusCode()).isEqualTo(200);

    // 5. Merchant B CANNOT read Merchant A's order (returns 404 to prevent resource enumeration)
    HttpResponse<String> readByB = call("GET", "/api/v1/merchant/orders/" + orderAId, merchantBToken, null);
    assertThat(readByB.statusCode()).isEqualTo(404);
  }
}
