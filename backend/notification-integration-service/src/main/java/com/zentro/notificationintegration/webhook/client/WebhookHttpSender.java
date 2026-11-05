package com.zentro.notificationintegration.webhook.client;

import com.zentro.notificationintegration.webhook.config.WebhookProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Outbound adapter for merchant endpoints (JDK HttpClient). One POST per attempt with the agreed headers; connect
 * timeout 2 s and response timeout 5 s by default; redirects are never followed; the response body is discarded,
 * only the status code is returned. Never throws for network problems: they become a {@link WebhookSendResult}.
 */
@Component
public class WebhookHttpSender {
  public static final String EVENT_ID_HEADER = "X-SwiftDrop-Event-Id";
  public static final String EVENT_TYPE_HEADER = "X-SwiftDrop-Event-Type";
  public static final String TIMESTAMP_HEADER = "X-SwiftDrop-Timestamp";
  public static final String SIGNATURE_HEADER = "X-SwiftDrop-Signature";
  static final String USER_AGENT = "SwiftDrop-Webhooks/1.0";

  /** One signed request to send. {@code body} is sent byte for byte as signed. */
  public record SignedRequest(URI url, String eventId, String eventType, long timestamp, String signature,
      String body) {
    @Override
    public String toString() {
      return "SignedRequest[eventId=" + eventId + ", eventType=" + eventType + "]";
    }
  }

  private final HttpClient client;
  private final Duration readTimeout;

  @Autowired
  public WebhookHttpSender(WebhookProperties properties) {
    this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.http().connectTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build(),
        Duration.ofMillis(properties.http().readTimeoutMs()));
  }

  WebhookHttpSender(HttpClient client, Duration readTimeout) {
    this.client = client;
    this.readTimeout = readTimeout;
  }

  public WebhookSendResult send(SignedRequest request) {
    HttpRequest http = HttpRequest.newBuilder(request.url())
        .timeout(readTimeout)
        .header("Content-Type", "application/json")
        .header("User-Agent", USER_AGENT)
        .header(EVENT_ID_HEADER, request.eventId())
        .header(EVENT_TYPE_HEADER, request.eventType())
        .header(TIMESTAMP_HEADER, Long.toString(request.timestamp()))
        .header(SIGNATURE_HEADER, request.signature())
        .POST(HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8))
        .build();
    try {
      HttpResponse<Void> response = client.send(http, HttpResponse.BodyHandlers.discarding());
      return WebhookSendResult.response(response.statusCode());
    } catch (HttpTimeoutException ex) {
      return WebhookSendResult.timeout();
    } catch (IOException ex) {
      return WebhookSendResult.networkError("network error: " + ex.getClass().getSimpleName());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return WebhookSendResult.networkError("interrupted");
    } catch (IllegalArgumentException ex) {
      return WebhookSendResult.networkError("invalid request");
    }
  }
}
