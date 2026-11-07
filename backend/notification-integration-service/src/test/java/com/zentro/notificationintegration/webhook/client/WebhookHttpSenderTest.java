package com.zentro.notificationintegration.webhook.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.notificationintegration.webhook.client.WebhookHttpSender.SignedRequest;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class WebhookHttpSenderTest {
  private final HttpClient mockClient = mock(HttpClient.class);
  private final Duration readTimeout = Duration.ofMillis(3000);
  private final WebhookHttpSender sender = new WebhookHttpSender(mockClient, readTimeout);

  private SignedRequest sampleRequest() {
    return new SignedRequest(
        URI.create("https://merchant.example.com/webhooks"),
        "evt-12345-67890",
        "parcel.status.updated",
        1764988800L,
        "t=1764988800,v1=abcdef1234567890",
        "{\"status\":\"IN_TRANSIT\",\"trackingRef\":\"ZT-ABCDEF1234\"}");
  }

  @Test
  @DisplayName("SignedRequest formats outgoing HTTP request with exact agreed headers, body and method")
  void formatsRequestProperly() throws Exception {
    @SuppressWarnings("unchecked")
    HttpResponse<Void> response = (HttpResponse<Void>) mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    doReturn(response).when(mockClient).send(any(), any());

    SignedRequest request = sampleRequest();
    WebhookSendResult result = sender.send(request);

    assertThat(result.hasResponse()).isTrue();
    assertThat(result.status()).isEqualTo(200);
    assertThat(result.error()).isNull();

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(mockClient).send(captor.capture(), any());
    HttpRequest sent = captor.getValue();

    assertThat(sent.uri()).isEqualTo(request.url());
    assertThat(sent.method()).isEqualTo("POST");
    assertThat(sent.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(sent.headers().firstValue("User-Agent")).contains(WebhookHttpSender.USER_AGENT);
    assertThat(sent.headers().firstValue(WebhookHttpSender.EVENT_ID_HEADER)).contains("evt-12345-67890");
    assertThat(sent.headers().firstValue(WebhookHttpSender.EVENT_TYPE_HEADER)).contains("parcel.status.updated");
    assertThat(sent.headers().firstValue(WebhookHttpSender.TIMESTAMP_HEADER)).contains("1764988800");
    assertThat(sent.headers().firstValue(WebhookHttpSender.SIGNATURE_HEADER)).contains("t=1764988800,v1=abcdef1234567890");
    assertThat(sent.timeout()).contains(readTimeout);
  }

  @ParameterizedTest(name = "HTTP response {0} mapped to status result")
  @ValueSource(ints = {200, 201, 204, 301, 302, 400, 401, 403, 404, 408, 429, 500, 502, 503, 504})
  @DisplayName("HTTP responses return their status code with null error")
  void handlesStatusCodes(int statusCode) throws Exception {
    @SuppressWarnings("unchecked")
    HttpResponse<Void> response = (HttpResponse<Void>) mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    doReturn(response).when(mockClient).send(any(), any());

    WebhookSendResult result = sender.send(sampleRequest());

    assertThat(result.hasResponse()).isTrue();
    assertThat(result.status()).isEqualTo(statusCode);
    assertThat(result.error()).isNull();
  }

  @Test
  @DisplayName("HttpTimeoutException translates to WebhookSendResult.timeout")
  void handlesHttpTimeoutException() throws Exception {
    doThrow(new HttpTimeoutException("Request timed out after 3000ms"))
        .when(mockClient).send(any(), any());

    WebhookSendResult result = sender.send(sampleRequest());

    assertThat(result.hasResponse()).isFalse();
    assertThat(result.status()).isNull();
    assertThat(result.error()).isEqualTo("timeout");
  }

  @Test
  @DisplayName("ConnectException translates to network error category with exception simple name")
  void handlesConnectException() throws Exception {
    doThrow(new ConnectException("Connection refused"))
        .when(mockClient).send(any(), any());

    WebhookSendResult result = sender.send(sampleRequest());

    assertThat(result.hasResponse()).isFalse();
    assertThat(result.status()).isNull();
    assertThat(result.error()).isEqualTo("network error: ConnectException");
  }

  @Test
  @DisplayName("SocketTimeoutException translates to network error category with exception simple name")
  void handlesSocketTimeoutException() throws Exception {
    doThrow(new SocketTimeoutException("Read timed out"))
        .when(mockClient).send(any(), any());

    WebhookSendResult result = sender.send(sampleRequest());

    assertThat(result.hasResponse()).isFalse();
    assertThat(result.status()).isNull();
    assertThat(result.error()).isEqualTo("network error: SocketTimeoutException");
  }

  @Test
  @DisplayName("General IOException translates to network error category")
  void handlesGeneralIOException() throws Exception {
    doThrow(new IOException("Broken pipe"))
        .when(mockClient).send(any(), any());

    WebhookSendResult result = sender.send(sampleRequest());

    assertThat(result.hasResponse()).isFalse();
    assertThat(result.status()).isNull();
    assertThat(result.error()).isEqualTo("network error: IOException");
  }

  @Test
  @DisplayName("InterruptedException restores thread interrupt flag and returns interrupted error")
  void handlesInterruptedException() throws Exception {
    doThrow(new InterruptedException("Interrupted during wait"))
        .when(mockClient).send(any(), any());

    WebhookSendResult result = sender.send(sampleRequest());

    assertThat(result.hasResponse()).isFalse();
    assertThat(result.status()).isNull();
    assertThat(result.error()).isEqualTo("interrupted");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    // Clear interrupted state
    Thread.interrupted();
  }

  @Test
  @DisplayName("SignedRequest toString omits payload body and sensitive signature secret")
  void signedRequestToStringIsRedacted() {
    SignedRequest request = sampleRequest();
    String stringRepresentation = request.toString();

    assertThat(stringRepresentation).contains("eventId=evt-12345-67890", "eventType=parcel.status.updated");
    assertThat(stringRepresentation).doesNotContain("ZT-ABCDEF1234", "abcdef1234567890", "IN_TRANSIT");
  }
}
