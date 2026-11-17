package com.zentro.identitypartner.user.service;

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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KeycloakAdminService {
  private static final Logger log = LoggerFactory.getLogger(KeycloakAdminService.class);

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String adminUrl;
  private final String realm;
  private final String adminUsername;
  private final String adminPassword;

  public KeycloakAdminService(
      ObjectMapper objectMapper,
      @Value("${zentro.keycloak.admin-url:http://localhost:8180}") String adminUrl,
      @Value("${zentro.keycloak.realm:zentro}") String realm,
      @Value("${zentro.keycloak.admin-username:admin}") String adminUsername,
      @Value("${zentro.keycloak.admin-password:}") String adminPassword) {
    this.objectMapper = objectMapper;
    this.adminUrl = adminUrl.replaceAll("/+$", "");
    this.realm = realm;
    this.adminUsername = adminUsername;
    this.adminPassword = adminPassword;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .build();
  }

  public String fetchAdminToken() throws Exception {
    String form = "grant_type=password&client_id=admin-cli"
        + "&username=" + urlEncode(adminUsername)
        + "&password=" + urlEncode(adminPassword);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(adminUrl + "/realms/master/protocol/openid-connect/token"))
        .timeout(Duration.ofSeconds(6))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      log.error("Could not obtain Keycloak admin token (HTTP {}): {}", response.statusCode(), response.body());
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Authentication with identity provider failed");
    }
    JsonNode node = objectMapper.readTree(response.body());
    return node.path("access_token").asText();
  }

  public boolean userExistsByEmail(String email) {
    try {
      String token = fetchAdminToken();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(adminUrl + "/admin/realms/" + urlEncode(realm) + "/users?email=" + urlEncode(email) + "&exact=true"))
          .timeout(Duration.ofSeconds(6))
          .header("Authorization", "Bearer " + token)
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        JsonNode arr = objectMapper.readTree(response.body());
        return arr.isArray() && !arr.isEmpty();
      }
    } catch (Exception ex) {
      log.warn("Error checking user existence in Keycloak for email {}: {}", email, ex.getMessage());
    }
    return false;
  }

  public String findUserIdByEmail(String email) {
    try {
      String token = fetchAdminToken();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(adminUrl + "/admin/realms/" + urlEncode(realm) + "/users?email=" + urlEncode(email) + "&exact=true"))
          .timeout(Duration.ofSeconds(6))
          .header("Authorization", "Bearer " + token)
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        JsonNode arr = objectMapper.readTree(response.body());
        if (arr.isArray() && !arr.isEmpty()) {
          return arr.get(0).path("id").asText();
        }
      }
    } catch (Exception ex) {
      log.warn("Error finding user ID by email in Keycloak for {}: {}", email, ex.getMessage());
    }
    return null;
  }

  public String createCourierUser(String email, String fullName, String phone, String tempPassword) {
    return createUserWithRole(email, fullName, phone, tempPassword, true, List.of("UPDATE_PASSWORD"), "COURIER");
  }

  public String createVerifiedCustomerUser(String email, String fullName, String password) {
    return createUserWithRole(email, fullName, null, password, false, List.of(), "CUSTOMER");
  }

  private String createUserWithRole(
      String email,
      String fullName,
      String phone,
      String password,
      boolean temporaryPassword,
      List<String> requiredActions,
      String realmRole) {
    try {
      String token = fetchAdminToken();
      String[] nameParts = splitName(fullName);

      Map<String, Object> credentialMap = Map.of(
          "type", "password",
          "value", password,
          "temporary", temporaryPassword
      );

      Map<String, Object> userPayload = new java.util.HashMap<>();
      userPayload.put("username", email);
      userPayload.put("email", email);
      userPayload.put("firstName", nameParts[0]);
      userPayload.put("lastName", nameParts[1]);
      userPayload.put("enabled", true);
      userPayload.put("emailVerified", true);
      userPayload.put("credentials", List.of(credentialMap));
      if (!requiredActions.isEmpty()) {
        userPayload.put("requiredActions", requiredActions);
      }
      if (phone != null && !phone.isBlank()) {
        userPayload.put("attributes", Map.of("phone_number", List.of(phone.trim())));
      }

      String body = objectMapper.writeValueAsString(userPayload);
      HttpRequest createRequest = HttpRequest.newBuilder()
          .uri(URI.create(adminUrl + "/admin/realms/" + urlEncode(realm) + "/users"))
          .timeout(Duration.ofSeconds(8))
          .header("Authorization", "Bearer " + token)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();

      HttpResponse<String> createResponse = httpClient.send(createRequest, HttpResponse.BodyHandlers.ofString());
      if (createResponse.statusCode() == 409) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "A user with email " + email + " already exists in Keycloak.");
      }
      if (createResponse.statusCode() < 200 || createResponse.statusCode() >= 300) {
        log.error("Failed to create Keycloak user (HTTP {}): {}", createResponse.statusCode(), createResponse.body());
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create user in Keycloak");
      }

      String userId = extractUserIdFromLocationOrSearch(createResponse, email, token);
      if (userId == null || userId.isBlank()) {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not resolve created Keycloak user ID");
      }

      if (realmRole != null && !realmRole.isBlank()) {
        assignRealmRole(userId, realmRole, token);
      }

      log.info("Successfully created Keycloak user: id={} email={} role={}", userId, email, realmRole);
      return userId;
    } catch (ResponseStatusException rse) {
      throw rse;
    } catch (Exception ex) {
      log.error("Exception creating Keycloak user {}: {}", email, ex.getMessage(), ex);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Keycloak user provisioning failed: " + ex.getMessage());
    }
  }

  public void deleteUser(String keycloakSubject) {
    if (keycloakSubject == null || keycloakSubject.isBlank()) {
      return;
    }
    try {
      String token = fetchAdminToken();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(adminUrl + "/admin/realms/" + urlEncode(realm) + "/users/" + urlEncode(keycloakSubject)))
          .timeout(Duration.ofSeconds(8))
          .header("Authorization", "Bearer " + token)
          .DELETE()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        log.warn("Keycloak user {} already not found during deletion", keycloakSubject);
        return;
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        log.warn("Keycloak user deletion returned HTTP {} for subject={}: {}", response.statusCode(), keycloakSubject, response.body());
      } else {
        log.info("Deleted Keycloak user subject={}", keycloakSubject);
      }
    } catch (Exception ex) {
      log.error("Failed to delete Keycloak user subject={}: {}", keycloakSubject, ex.getMessage());
    }
  }

  public void resetPassword(String keycloakSubject, String newPassword) {
    if (keycloakSubject == null || keycloakSubject.isBlank()) {
      throw new IllegalArgumentException("Subject cannot be blank");
    }
    try {
      String token = fetchAdminToken();
      Map<String, Object> payload = Map.of(
          "type", "password",
          "value", newPassword,
          "temporary", false
      );
      String body = objectMapper.writeValueAsString(payload);
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(adminUrl + "/admin/realms/" + urlEncode(realm) + "/users/" + urlEncode(keycloakSubject) + "/reset-password"))
          .timeout(Duration.ofSeconds(8))
          .header("Authorization", "Bearer " + token)
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        log.error("Failed to reset password for user {} in Keycloak (HTTP {}): {}", keycloakSubject, response.statusCode(), response.body());
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to reset password in Keycloak");
      }
      log.info("Successfully reset password for user {} in Keycloak", keycloakSubject);
    } catch (ResponseStatusException rse) {
      throw rse;
    } catch (Exception ex) {
      log.error("Exception resetting password for user {}: {}", keycloakSubject, ex.getMessage(), ex);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Keycloak password reset failed: " + ex.getMessage());
    }
  }

  public void assignRealmRole(String userId, String roleName, String token) throws Exception {
    String roleJson = fetchRoleJson(roleName, token);
    if (roleJson == null) {
      log.warn("Role {} not found in Keycloak realm", roleName);
      return;
    }
    String mappingUrl = adminUrl + "/admin/realms/" + urlEncode(realm)
        + "/users/" + urlEncode(userId) + "/role-mappings/realm";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(mappingUrl))
        .timeout(Duration.ofSeconds(6))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("[" + roleJson + "]"))
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      log.warn("Failed to assign role {} to user {}: HTTP {}", roleName, userId, response.statusCode());
    }
  }

  private String fetchRoleJson(String roleName, String token) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(adminUrl + "/admin/realms/" + urlEncode(realm) + "/roles/" + urlEncode(roleName)))
        .timeout(Duration.ofSeconds(6))
        .header("Authorization", "Bearer " + token)
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      return response.body();
    }
    return null;
  }

  private String extractUserIdFromLocationOrSearch(HttpResponse<String> response, String email, String token) {
    String location = response.headers().firstValue("Location").orElse(null);
    if (location != null && !location.isBlank()) {
      int lastSlash = location.lastIndexOf('/');
      if (lastSlash != -1 && lastSlash < location.length() - 1) {
        return location.substring(lastSlash + 1);
      }
    }
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(adminUrl + "/admin/realms/" + urlEncode(realm) + "/users?email=" + urlEncode(email) + "&exact=true"))
          .timeout(Duration.ofSeconds(6))
          .header("Authorization", "Bearer " + token)
          .GET()
          .build();
      HttpResponse<String> searchRes = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (searchRes.statusCode() >= 200 && searchRes.statusCode() < 300) {
        JsonNode arr = objectMapper.readTree(searchRes.body());
        if (arr.isArray() && !arr.isEmpty()) {
          return arr.get(0).path("id").asText();
        }
      }
    } catch (Exception ex) {
      log.warn("Error searching for user by email {}: {}", email, ex.getMessage());
    }
    return null;
  }

  private static String[] splitName(String fullName) {
    if (fullName == null || fullName.isBlank()) {
      return new String[] {"", ""};
    }
    String trimmed = fullName.trim();
    int spaceIndex = trimmed.indexOf(' ');
    if (spaceIndex == -1) {
      return new String[] {trimmed, ""};
    }
    return new String[] {trimmed.substring(0, spaceIndex).trim(), trimmed.substring(spaceIndex + 1).trim()};
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
