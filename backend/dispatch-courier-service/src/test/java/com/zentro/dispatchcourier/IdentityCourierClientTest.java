package com.zentro.dispatchcourier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class IdentityCourierClientTest {
  private static final UUID PROFILE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private MockRestServiceServer server;
  private IdentityCourierClient client;

  @BeforeEach void setup() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client = new IdentityCourierClient(builder.baseUrl("http://identity").build());
  }

  private static String profile(boolean active, String status) {
    return "{\"id\":\"" + PROFILE + "\",\"userId\":\"" + USER
        + "\",\"name\":\"Courier\",\"active\":" + active + ",\"accountStatus\":\"" + status + "\"}";
  }

  @Test void listsOnlyEffectivelyActiveCouriersAndForwardsAuthentication() {
    server.expect(requestTo("http://identity/api/v1/admin/couriers?active=true"))
        .andExpect(header("Authorization", "Bearer dispatcher-token"))
        .andRespond(withSuccess("[" + profile(true, "ACTIVE") + "," + profile(false, "ACTIVE")
            + "," + profile(true, "SUSPENDED") + "]", MediaType.APPLICATION_JSON));
    assertThat(client.listEligible("dispatcher-token")).extracting(IdentityCourierClient.Courier::id)
        .containsExactly(PROFILE);
    server.verify();
  }

  @Test void revalidatesUsingUserIdAndReturnsCanonicalProfileId() {
    server.expect(requestTo("http://identity/api/v1/admin/couriers/" + USER))
        .andRespond(withSuccess(profile(true, "ACTIVE"), MediaType.APPLICATION_JSON));
    assertThat(client.requireEligible(USER, "token").id()).isEqualTo(PROFILE);
  }

  @Test void inactiveCourierConflicts() {
    server.expect(anything()).andRespond(withSuccess(profile(false, "ACTIVE"), MediaType.APPLICATION_JSON));
    assertStatus(() -> client.requireEligible(PROFILE, "token"), 409);
  }

  @ParameterizedTest @ValueSource(ints = {401, 403, 404, 500})
  void mapsDependencyStatus(int status) {
    server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status)));
    assertStatus(() -> client.requireEligible(PROFILE, "token"), status == 500 ? 503 : status);
  }

  @Test void networkFailureFailsClosed() {
    server.expect(anything()).andRespond(withException(new IOException("timeout")));
    assertStatus(() -> client.listEligible("token"), 503);
  }

  @Test void malformedResponseFailsClosed() {
    server.expect(anything()).andRespond(withSuccess("[{}]", MediaType.APPLICATION_JSON));
    assertStatus(() -> client.listEligible("token"), 503);
  }

  private static void assertStatus(Runnable operation, int expected) {
    assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class,
        error -> assertThat(error.getStatusCode().value()).isEqualTo(expected));
  }
}
