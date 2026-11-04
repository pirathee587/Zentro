package com.zentro.dispatchcourier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ParcelStatusClientTest {
  MockRestServiceServer server;
  ParcelStatusClient client;
  ServiceTokenProvider tokens;
  UUID parcel = UUID.randomUUID();
  @BeforeEach void setup() {
    var builder = RestClient.builder().baseUrl("http://parcel");
    server = MockRestServiceServer.bindTo(builder).build();
    tokens = mock(ServiceTokenProvider.class);
    when(tokens.token()).thenReturn("service-test-token");
    client = new ParcelStatusClient(builder.build(), tokens);
  }
  @ParameterizedTest @ValueSource(booleans = {true, false})
  void deliveredIncludingIdempotentReplayUsesOnlyServiceAuth(boolean changed) {
    server.expect(requestTo("http://parcel/api/v1/parcels/" + parcel + "/status"))
        .andExpect(method(HttpMethod.PATCH)).andExpect(header("Authorization", "Bearer service-test-token"))
        .andExpect(content().json("{\"status\":\"DELIVERED\"}"))
        .andRespond(withSuccess("{\"parcelId\":\"" + parcel + "\",\"status\":\"DELIVERED\",\"changed\":" + changed + "}", MediaType.APPLICATION_JSON));
    client.complete(parcel); server.verify();
  }
  @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 409, 422, 429, 500, 503})
  void classifiesFailuresWithoutLeakingDependencyBody(int status) {
    server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status)).body("private dependency details"));
    String state = status == 409 ? "REJECTED" : status == 429 || status >= 500 ? "PENDING" : "BLOCKED";
    assertThatThrownBy(() -> client.complete(parcel)).isInstanceOfSatisfying(CompletionFailure.class,
        failure -> { assertThat(failure.state()).isEqualTo(state); assertThat(failure.getMessage()).doesNotContain("private"); });
    if (status == 401) verify(tokens).invalidate();
  }
  @Test void timeoutRemainsRetryable() {
    server.expect(anything()).andRespond(withException(new IOException("timeout")));
    assertThatThrownBy(() -> client.complete(parcel)).isInstanceOfSatisfying(CompletionFailure.class,
        failure -> assertThat(failure.state()).isEqualTo("PENDING"));
  }
  @ParameterizedTest @ValueSource(strings = {"{}", "{\"status\":\"DELIVERED\"}", "not-json"})
  void neverConfirmsMalformedResponses(String body) {
    server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.complete(parcel)).isInstanceOf(CompletionFailure.class);
  }
}
