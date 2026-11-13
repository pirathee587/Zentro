package com.zentro.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Uses only two sequential disposable Keycloak lifecycles; no shared or deployed IdP. */
class KeycloakAdminTokenLifecycleIT {
  @Test
  @Timeout(180)
  void freshIdentityServerDoesNotReuseAnUnexpiredTokenFromThePreviousInstance() throws Exception {
    try {
      DisposableGatewayStack.startKeycloak();
      String previousToken = DisposableGatewayStack.adminToken();
      // Make the stale cache hit deterministic, independent of container startup timing.
      DisposableGatewayStack.adminAccessTokenExpiresAt = Instant.now().plusSeconds(600);
      DisposableGatewayStack.KEYCLOAK.stop();

      DisposableGatewayStack.startKeycloak();
      assertThat(DisposableGatewayStack.sendRaw("GET",
          DisposableGatewayStack.keycloak() + "/admin/realms/zentro/clients", previousToken, null).statusCode())
          .as("fresh Keycloak must reject the previous instance's token").isEqualTo(401);

      var response = DisposableGatewayStack.adminCall("POST", "/admin/realms/zentro/clients",
          "{\"clientId\":\"cache-regression-" + UUID.randomUUID()
              + "\",\"enabled\":true,\"protocol\":\"openid-connect\",\"publicClient\":true}");
      assertThat(response.statusCode()).as("fixture must acquire current-instance credentials").isEqualTo(201);
      assertThat(java.util.Objects.equals(DisposableGatewayStack.adminAccessToken, previousToken))
          .as("current-instance token must replace the stale cached token").isFalse();
    } finally {
      DisposableGatewayStack.KEYCLOAK.stop();
    }
  }
}
