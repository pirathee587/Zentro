package com.zentro.notificationintegration.webhook.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.notificationintegration.webhook.client.IdentityMerchantClient;
import com.zentro.notificationintegration.webhook.exception.MerchantVerificationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Merchant webhook API through the real security filter chain (bearer token -> resource server -> Keycloak realm
 * role converter); only the signature check (JwtDecoder) and the S1 approval call are replaced. Uses a PostgreSQL
 * test container, never the shared database. Endpoint URLs use public IP literals, so no DNS is needed.
 * Skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MerchantWebhookApiTest {
  private static final String PATH = "/api/v1/merchant/webhooks";
  private static final String URL_A = "https://93.184.216.34/hooks/swiftdrop";
  private static final String URL_B = "https://93.184.216.34/hooks/other";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("zentro.security.issuer-uri", () -> "http://localhost:8180/realms/zentro");
    registry.add("zentro.security.audience", () -> "zentro-api");
    // Delivery stays off (no Kafka); the registration API only needs the encryption key. Test-only key.
    registry.add("zentro.webhooks.secret-key", () -> "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper objectMapper;
  @MockBean JwtDecoder jwtDecoder;
  @MockBean IdentityMerchantClient identity;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM notification.webhook_delivery");
    jdbc.update("DELETE FROM notification.webhook_subscription");
    when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("invalid token"));
  }

  /** A bearer token that decodes to this subject with these Keycloak realm roles. */
  private String token(String subject, String... roles) {
    String value = "token-" + subject + "-" + String.join("-", roles);
    Jwt jwt = Jwt.withTokenValue(value)
        .header("alg", "RS256")
        .subject(subject)
        .issuer("http://localhost:8180/realms/zentro")
        .audience(List.of("zentro-api"))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .claim("realm_access", Map.of("roles", List.of(roles)))
        .build();
    doReturn(jwt).when(jwtDecoder).decode(value);
    return "Bearer " + value;
  }

  private String merchant(String subject) {
    return token(subject, "MERCHANT");
  }

  private MockHttpServletResponse perform(MockHttpServletRequestBuilder builder, String authorization)
      throws Exception {
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return mvc.perform(builder).andReturn().getResponse();
  }

  private MockHttpServletResponse register(String authorization, String url) throws Exception {
    return perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("url", url))), authorization);
  }

  private JsonNode json(MockHttpServletResponse response) throws Exception {
    return objectMapper.readTree(response.getContentAsString());
  }

  private int subscriptions() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM notification.webhook_subscription", Integer.class);
  }

  private UUID subscriptionId(String merchantId) {
    return jdbc.queryForObject("SELECT id FROM notification.webhook_subscription WHERE merchant_id = ?",
        UUID.class, merchantId);
  }

  private void insertDelivery(UUID subscriptionId, String status, int minutesAgo) {
    jdbc.update("""
        INSERT INTO notification.webhook_delivery (subscription_id, event_id, event_type, parcel_id, payload,
            payload_sha256, status, attempts, response_status, created_at)
        VALUES (?, ?, 'parcel.status.updated', ?, '{}', repeat('a', 64), ?, 1, 200, now() - make_interval(mins => ?))
        """, subscriptionId, UUID.randomUUID(), UUID.randomUUID(), status, minutesAgo);
  }

  // ---- authentication and roles

  @ParameterizedTest(name = "{0} {1} without a token -> 401")
  @CsvSource({"GET, ''", "POST, ''", "DELETE, ''", "POST, /secret", "GET, /deliveries"})
  @DisplayName("Every endpoint needs a valid bearer token")
  void unauthenticated(String method, String suffix) throws Exception {
    MockHttpServletResponse response = perform(request(HttpMethod.valueOf(method), PATH + suffix)
        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + URL_A + "\"}"), null);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(perform(get(PATH + suffix), "Bearer not-a-valid-token").getStatus()).isEqualTo(401);
    assertThat(subscriptions()).isZero();
  }

  @ParameterizedTest(name = "{0} -> 403")
  @ValueSource(strings = {"CUSTOMER", "COURIER", "DISPATCHER", "SERVICE"})
  @DisplayName("Tokens without the MERCHANT role are forbidden and store nothing")
  void otherRolesForbidden(String role) throws Exception {
    String auth = token("user-" + role.toLowerCase(), role);

    assertThat(register(auth, URL_A).getStatus()).isEqualTo(403);
    assertThat(perform(get(PATH), auth).getStatus()).isEqualTo(403);
    assertThat(perform(get(PATH + "/deliveries"), auth).getStatus()).isEqualTo(403);
    assertThat(perform(post(PATH + "/secret"), auth).getStatus()).isEqualTo(403);
    assertThat(perform(delete(PATH), auth).getStatus()).isEqualTo(403);
    assertThat(subscriptions()).isZero();
  }

  @Test
  @DisplayName("Realm roles match regardless of case (merchant -> ROLE_MERCHANT)")
  void lowercaseRole() throws Exception {
    assertThat(register(token("merchant-lower", "merchant"), URL_A).getStatus()).isEqualTo(201);
  }

  // ---- registration and the one-time secret

  @Test
  @DisplayName("Register: 201, the secret is returned once, not cacheable, and stored only encrypted")
  void registerShowsSecretOnce() throws Exception {
    String auth = merchant("merchant-a");

    MockHttpServletResponse created = register(auth, URL_A);

    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(created.getHeader("Location")).isEqualTo(PATH);
    assertThat(created.getHeader("Cache-Control")).contains("no-store");
    JsonNode body = json(created);
    String secret = body.get("secret").asText();
    assertThat(secret).matches("whsec_[A-Za-z0-9_-]{43}");
    assertThat(body.get("secretPrefix").asText()).isEqualTo(secret.substring(0, 8));
    assertThat(body.get("url").asText()).isEqualTo(URL_A);
    assertThat(body.get("active").asBoolean()).isTrue();
    String stored = jdbc.queryForObject(
        "SELECT secret_ciphertext FROM notification.webhook_subscription WHERE merchant_id = 'merchant-a'",
        String.class);
    assertThat(stored).startsWith("v1.").doesNotContain(secret).doesNotContain(secret.substring(6));

    MockHttpServletResponse view = perform(get(PATH), auth);
    assertThat(view.getStatus()).isEqualTo(200);
    assertThat(json(view).has("secret")).isFalse();
    assertThat(view.getContentAsString()).doesNotContain(secret);
    assertThat(json(view).get("secretPrefix").asText()).isEqualTo(secret.substring(0, 8));
  }

  @Test
  @DisplayName("Registering again replaces URL and secret (200) and keeps one endpoint per merchant")
  void registerAgainReplaces() throws Exception {
    String auth = merchant("merchant-a");
    String first = json(register(auth, URL_A)).get("secret").asText();

    MockHttpServletResponse replaced = register(auth, URL_B);

    assertThat(replaced.getStatus()).isEqualTo(200);
    assertThat(json(replaced).get("secret").asText()).isNotEqualTo(first);
    assertThat(json(replaced).get("url").asText()).isEqualTo(URL_B);
    assertThat(subscriptions()).isEqualTo(1);
  }

  @Test
  @DisplayName("Rotate: a new secret is returned once; the old prefix is replaced")
  void rotateSecret() throws Exception {
    String auth = merchant("merchant-a");
    String first = json(register(auth, URL_A)).get("secret").asText();

    MockHttpServletResponse rotated = perform(post(PATH + "/secret"), auth);

    assertThat(rotated.getStatus()).isEqualTo(200);
    assertThat(rotated.getHeader("Cache-Control")).contains("no-store");
    String second = json(rotated).get("secret").asText();
    assertThat(second).isNotEqualTo(first).startsWith("whsec_");
    assertThat(json(perform(get(PATH), auth)).get("secretPrefix").asText()).isEqualTo(second.substring(0, 8));
  }

  @Test
  @DisplayName("Disable: 204, the endpoint shows inactive, rotation then returns 404; registering re-enables")
  void disable() throws Exception {
    String auth = merchant("merchant-a");
    register(auth, URL_A);

    assertThat(perform(delete(PATH), auth).getStatus()).isEqualTo(204);
    assertThat(json(perform(get(PATH), auth)).get("active").asBoolean()).isFalse();
    assertThat(perform(delete(PATH), auth).getStatus()).isEqualTo(204);
    assertThat(json(perform(post(PATH + "/secret"), auth)).get("code").asText()).isEqualTo("WEBHOOK_NOT_FOUND");

    assertThat(register(auth, URL_A).getStatus()).isEqualTo(200);
    assertThat(json(perform(get(PATH), auth)).get("active").asBoolean()).isTrue();
  }

  // ---- validation

  @ParameterizedTest(name = "[{index}] \"{0}\"")
  @ValueSource(strings = {
      "http://93.184.216.34/hooks", "https://10.0.0.5/hooks", "https://192.168.1.20/hooks", "https://127.0.0.1/hooks",
      "https://169.254.169.254/latest/meta-data", "https://[::1]/hooks", "https://user:pw@93.184.216.34/hooks",
      "not a url", ""
  })
  @DisplayName("Invalid or blocked URLs return 422 with a url field error and store nothing")
  void invalidUrl(String url) throws Exception {
    MockHttpServletResponse response = register(merchant("merchant-a"), url);

    assertThat(response.getStatus()).isEqualTo(422);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    JsonNode body = json(response);
    assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(body.get("fieldErrors").has("url")).isTrue();
    assertThat(subscriptions()).isZero();
  }

  @Test
  @DisplayName("A URL over 2048 characters returns 422; a missing body field returns 422; bad JSON returns 400")
  void bodyValidation() throws Exception {
    String auth = merchant("merchant-a");
    assertThat(register(auth, "https://93.184.216.34/" + "a".repeat(2048)).getStatus()).isEqualTo(422);
    assertThat(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"), auth).getStatus())
        .isEqualTo(422);
    MockHttpServletResponse malformed = perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{"), auth);
    assertThat(malformed.getStatus()).isEqualTo(400);
    assertThat(json(malformed).get("code").asText()).isEqualTo("MALFORMED_REQUEST");
    assertThat(subscriptions()).isZero();
  }

  // ---- merchant approval (S1)

  @Test
  @DisplayName("A merchant that is not approved gets 403 and nothing is stored")
  void notApproved() throws Exception {
    doThrow(new MerchantVerificationException(HttpStatus.FORBIDDEN, "MERCHANT_NOT_APPROVED",
        "Merchant application is not approved.")).when(identity).requireApprovedMerchant(anyString());

    MockHttpServletResponse response = register(merchant("merchant-pending"), URL_A);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(json(response).get("code").asText()).isEqualTo("MERCHANT_NOT_APPROVED");
    assertThat(subscriptions()).isZero();
  }

  @Test
  @DisplayName("When S1 is unavailable, registration returns 503 and stores nothing")
  void identityUnavailable() throws Exception {
    doThrow(new MerchantVerificationException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE",
        "Merchant verification is unavailable. Please try again.")).when(identity).requireApprovedMerchant(anyString());

    MockHttpServletResponse response = register(merchant("merchant-a"), URL_A);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(json(response).get("code").asText()).isEqualTo("IDENTITY_UNAVAILABLE");
    assertThat(subscriptions()).isZero();
  }

  @Test
  @DisplayName("Approval is checked with the caller's own token before anything is stored")
  void approvalUsesCallerToken() throws Exception {
    String auth = merchant("merchant-a");
    register(auth, URL_A);
    verify(identity).requireApprovedMerchant(auth.substring("Bearer ".length()));
    perform(get(PATH), auth);
    perform(get(PATH + "/deliveries"), auth);
    verify(identity, org.mockito.Mockito.times(1)).requireApprovedMerchant(anyString());
  }

  // ---- merchant isolation and deliveries

  @Test
  @DisplayName("A merchant never sees or changes another merchant's endpoint or deliveries (404 / empty)")
  void merchantIsolation() throws Exception {
    String alice = merchant("merchant-alice");
    String bob = merchant("merchant-bob");
    register(alice, URL_A);
    insertDelivery(subscriptionId("merchant-alice"), "SUCCEEDED", 1);

    assertThat(perform(get(PATH), bob).getStatus()).isEqualTo(404);
    assertThat(json(perform(get(PATH), bob)).get("code").asText()).isEqualTo("WEBHOOK_NOT_FOUND");
    assertThat(perform(delete(PATH), bob).getStatus()).isEqualTo(404);
    assertThat(perform(post(PATH + "/secret"), bob).getStatus()).isEqualTo(404);
    JsonNode bobDeliveries = json(perform(get(PATH + "/deliveries"), bob));
    assertThat(bobDeliveries.get("items")).isEmpty();
    assertThat(bobDeliveries.get("total").asLong()).isZero();

    assertThat(json(perform(get(PATH), alice)).get("active").asBoolean()).isTrue();
    assertThat(json(perform(get(PATH + "/deliveries"), alice)).get("total").asLong()).isEqualTo(1);
  }

  @Test
  @DisplayName("Deliveries are listed newest first with outcome fields, paged, and size is limited to 100")
  void deliveriesPaging() throws Exception {
    String auth = merchant("merchant-a");
    register(auth, URL_A);
    UUID subscription = subscriptionId("merchant-a");
    insertDelivery(subscription, "SUCCEEDED", 3);
    insertDelivery(subscription, "RETRY", 2);
    insertDelivery(subscription, "FAILED", 1);

    JsonNode first = json(perform(get(PATH + "/deliveries").param("page", "0").param("size", "2"), auth));
    assertThat(first.get("total").asLong()).isEqualTo(3);
    assertThat(first.get("items")).hasSize(2);
    assertThat(first.get("items").get(0).get("status").asText()).isEqualTo("FAILED");
    assertThat(first.get("items").get(0).get("responseStatus").asInt()).isEqualTo(200);
    assertThat(first.get("items").get(0).has("payload")).isFalse();
    JsonNode second = json(perform(get(PATH + "/deliveries").param("page", "1").param("size", "2"), auth));
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("items").get(0).get("status").asText()).isEqualTo("SUCCEEDED");

    assertThat(json(perform(get(PATH + "/deliveries").param("size", "1000"), auth)).get("size").asInt()).isEqualTo(100);
    assertThat(perform(get(PATH + "/deliveries").param("page", "x"), auth).getStatus()).isEqualTo(400);
  }

  @Test
  @DisplayName("GET without a registered endpoint returns 404")
  void noEndpoint() throws Exception {
    MockHttpServletResponse response = perform(get(PATH), merchant("merchant-new"));
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentType()).startsWith("application/problem+json");
  }
}
