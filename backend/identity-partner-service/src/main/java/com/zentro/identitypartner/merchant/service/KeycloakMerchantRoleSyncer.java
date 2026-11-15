package com.zentro.identitypartner.merchant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Synchronizes the {@code MERCHANT} realm role in Keycloak whenever a merchant application
 * transitions to or from {@code APPROVED}.
 */
@Component
public class KeycloakMerchantRoleSyncer {
  private static final Logger log = LoggerFactory.getLogger(KeycloakMerchantRoleSyncer.class);
  private static final String MERCHANT_ROLE = "MERCHANT";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String adminUrl;
  private final String realm;
  private final String adminUsername;
  private final String adminPassword;

  public KeycloakMerchantRoleSyncer(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      @Value("${zentro.keycloak.admin-url:http://localhost:8180}") String adminUrl,
      @Value("${zentro.keycloak.realm:zentro}") String realm,
      @Value("${zentro.keycloak.admin-username:admin}") String adminUsername,
      @Value("${zentro.keycloak.admin-password:}") String adminPassword) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.adminUrl = adminUrl.replaceAll("/+$", "");
    this.realm = realm;
    this.adminUsername = adminUsername;
    this.adminPassword = adminPassword;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void syncApprovedMerchantsOnStartup() {
    if (adminPassword == null || adminPassword.isBlank()) {
      return;
    }
    try {
      List<String> subjects = jdbc.queryForList("""
          SELECT u.keycloak_subject
            FROM identity.merchant_application m
            JOIN identity.app_user u ON u.id = m.app_user_id
           WHERE m.status = 'APPROVED'
          """, String.class);
      for (String subject : subjects) {
        syncBySubject(subject, true);
      }
    } catch (Exception ex) {
      log.warn("Could not sync approved merchant roles with Keycloak on startup: {}", ex.getMessage());
    }
  }

  public void syncByAppUserId(UUID appUserId, boolean grantMerchantRole) {
    if (appUserId == null || adminPassword == null || adminPassword.isBlank()) {
      return;
    }
    try {
      List<String> subjects = jdbc.queryForList(
          "SELECT keycloak_subject FROM identity.app_user WHERE id = ?",
          String.class,
          appUserId);
      if (!subjects.isEmpty()) {
        syncBySubject(subjects.getFirst(), grantMerchantRole);
      }
    } catch (Exception ex) {
      log.warn("Could not look up Keycloak subject for appUserId={}: {}", appUserId, ex.getMessage());
    }
  }

  public void syncBySubject(String keycloakSubject, boolean grantMerchantRole) {
    if (keycloakSubject == null || keycloakSubject.isBlank()
        || adminPassword == null || adminPassword.isBlank()) {
      return;
    }
    try {
      String token = fetchAdminToken();
      if (token == null || token.isBlank()) {
        return;
      }
      String roleJson = fetchMerchantRoleJson(token);
      if (roleJson == null || roleJson.isBlank()) {
        return;
      }
      String mappingUrl = adminUrl + "/admin/realms/" + urlEncode(realm)
          + "/users/" + urlEncode(keycloakSubject) + "/role-mappings/realm";
      HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
          .uri(URI.create(mappingUrl))
          .timeout(Duration.ofSeconds(5))
          .header("Authorization", "Bearer " + token)
          .header("Content-Type", "application/json");

      HttpRequest request = grantMerchantRole
          ? reqBuilder.POST(HttpRequest.BodyPublishers.ofString("[" + roleJson + "]")).build()
          : reqBuilder.method("DELETE", HttpRequest.BodyPublishers.ofString("[" + roleJson + "]")).build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        log.info("Synced Keycloak {} role for subject={} (granted={})",
            MERCHANT_ROLE, keycloakSubject, grantMerchantRole);
      } else {
        log.warn("Keycloak role sync returned HTTP {} for subject={}",
            response.statusCode(), keycloakSubject);
      }
    } catch (Exception ex) {
      log.warn("Failed to sync Keycloak {} role for subject={}: {}",
          MERCHANT_ROLE, keycloakSubject, ex.getMessage());
    }
  }

  private String fetchAdminToken() throws Exception {
    String form = "grant_type=password&client_id=admin-cli"
        + "&username=" + urlEncode(adminUsername)
        + "&password=" + urlEncode(adminPassword);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(adminUrl + "/realms/master/protocol/openid-connect/token"))
        .timeout(Duration.ofSeconds(5))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      log.warn("Could not obtain Keycloak admin token (HTTP {})", response.statusCode());
      return null;
    }
    JsonNode node = objectMapper.readTree(response.body());
    return node.path("access_token").asText(null);
  }

  private String fetchMerchantRoleJson(String token) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(adminUrl + "/admin/realms/" + urlEncode(realm) + "/roles/" + MERCHANT_ROLE))
        .timeout(Duration.ofSeconds(5))
        .header("Authorization", "Bearer " + token)
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      log.warn("Could not fetch Keycloak role {} (HTTP {})", MERCHANT_ROLE, response.statusCode());
      return null;
    }
    return response.body();
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}

