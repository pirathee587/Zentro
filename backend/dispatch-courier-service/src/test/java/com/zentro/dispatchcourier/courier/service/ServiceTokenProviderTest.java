package com.zentro.dispatchcourier.courier.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.zentro.dispatchcourier.exception.CompletionFailure;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ServiceTokenProviderTest {
  @ParameterizedTest @ValueSource(ints = {400, 401, 403, 429, 500, 503})
  void rateLimitingAndServerFailuresRemainRetryable(int status) {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var provider = new ServiceTokenProvider(builder.build(), "http://identity/token", "test", "test-only-placeholder");
    server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status)));
    assertThatThrownBy(provider::token).isInstanceOfSatisfying(CompletionFailure.class,
        failure -> assertThat(failure.state()).isEqualTo(status == 429 || status >= 500 ? "PENDING" : "BLOCKED"));
    server.verify();
  }
  @Test void missingCredentialsFailClosedWithoutHttp() {
    var provider = new ServiceTokenProvider(RestClient.create(), "", "", "");
    assertThatThrownBy(provider::token).isInstanceOfSatisfying(CompletionFailure.class,
        error -> assertThat(error.state()).isEqualTo("BLOCKED"));
  }
  @Test void clientCredentialsAreFormEncodedCachedAndInvalidated() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var provider = new ServiceTokenProvider(builder.build(), "http://identity/token", "test-client", "test-only-placeholder");
    for (int i = 0; i < 2; i++) server.expect(requestTo("http://identity/token"))
        .andExpect(method(HttpMethod.POST)).andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=client_credentials")))
        .andRespond(withSuccess("{\"access_token\":\"test-response-token\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    assertThat(provider.token()).isEqualTo("test-response-token");
    assertThat(provider.token()).isEqualTo("test-response-token");
    provider.invalidate(); provider.token(); server.verify();
  }
  @Test void transientTokenFailureAndMalformedTokenResponseAreDistinct() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var provider = new ServiceTokenProvider(builder.build(), "http://identity/token", "test", "test-only-placeholder");
    server.expect(anything()).andRespond(withException(new IOException("timeout")));
    server.expect(anything()).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    assertThatThrownBy(provider::token).isInstanceOfSatisfying(CompletionFailure.class,
        failure -> assertThat(failure.state()).isEqualTo("PENDING"));
    assertThatThrownBy(provider::token).isInstanceOfSatisfying(CompletionFailure.class,
        failure -> assertThat(failure.state()).isEqualTo("BLOCKED"));
  }
}

