package com.zentro.identitypartner.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeycloakRealmConfigurationTest {

  private static JsonNode realmConfig;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void loadRealmExport() throws Exception {
    Path candidate = Paths.get("../../infra/keycloak/realm-export.json");
    if (!candidate.toFile().exists()) {
      candidate = Paths.get("infra/keycloak/realm-export.json");
    }
    File realmFile = candidate.toFile();
    assertThat(realmFile).exists();
    realmConfig = MAPPER.readTree(realmFile);
  }

  @Test
  @DisplayName("Realm name is zentro and default role is CUSTOMER only")
  void verifyRealmBasics() {
    assertThat(realmConfig.get("realm").asText()).isEqualTo("zentro");
    JsonNode defaultRoles = realmConfig.get("defaultRoles");
    List<String> defaults = new ArrayList<>();
    defaultRoles.forEach(r -> defaults.add(r.asText()));
    assertThat(defaults).containsExactly("CUSTOMER");
  }

  @Test
  @DisplayName("Realm defines required roles including SERVICE and DISPATCHER without separate ADMIN")
  void verifyRealmRoles() {
    JsonNode realmRoles = realmConfig.path("roles").path("realm");
    List<String> roleNames = new ArrayList<>();
    realmRoles.forEach(r -> roleNames.add(r.path("name").asText()));
    assertThat(roleNames).contains("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE");
    assertThat(roleNames).doesNotContain("ADMIN");
  }

  @Test
  @DisplayName("Client zentro-web contains audience, role, and permissions claim mappers")
  void verifyZentroWebProtocolMappers() {
    JsonNode clients = realmConfig.path("clients");
    JsonNode zentroWeb = null;
    for (JsonNode c : clients) {
      if ("zentro-web".equals(c.path("clientId").asText())) {
        zentroWeb = c;
        break;
      }
    }
    assertThat(zentroWeb).isNotNull();

    JsonNode mappers = zentroWeb.path("protocolMappers");
    JsonNode audienceMapper = null;
    JsonNode permissionsMapper = null;
    JsonNode roleMapper = null;

    for (JsonNode m : mappers) {
      String name = m.path("name").asText();
      if ("zentro-api audience".equals(name)) {
        audienceMapper = m;
      } else if ("permissions claim".equals(name)) {
        permissionsMapper = m;
      } else if (name.contains("realm roles")) {
        roleMapper = m;
      }
    }

    assertThat(audienceMapper).isNotNull();
    assertThat(audienceMapper.path("config").path("included.custom.audience").asText()).isEqualTo("zentro-api");

    assertThat(roleMapper).isNotNull();

    assertThat(permissionsMapper).isNotNull();
    JsonNode permConfig = permissionsMapper.path("config");
    assertThat(permConfig.path("user.attribute").asText()).isEqualTo("permissions");
    assertThat(permConfig.path("claim.name").asText()).isEqualTo("permissions");
    assertThat(permConfig.path("multivalued").asText()).isEqualTo("true");
    assertThat(permConfig.path("access.token.claim").asText()).isEqualTo("true");
  }

  @Test
  @DisplayName("Keycloak declarative user profile restricts permissions attribute to admin-edit only")
  void verifyUserProfilePermissionsAttributeSecurity() throws Exception {
    JsonNode components = realmConfig.path("components").path("org.keycloak.userprofile.UserProfileProvider");
    assertThat(components.isArray()).isTrue();
    JsonNode provider = components.get(0);
    String rawConfig = provider.path("config").path("kc.user.profile.config").get(0).asText();

    JsonNode parsedProfile = MAPPER.readTree(rawConfig);
    JsonNode attributes = parsedProfile.path("attributes");
    JsonNode permissionsAttr = null;
    for (JsonNode attr : attributes) {
      if ("permissions".equals(attr.path("name").asText())) {
        permissionsAttr = attr;
        break;
      }
    }

    assertThat(permissionsAttr).isNotNull();
    assertThat(permissionsAttr.path("multivalued").asBoolean()).isTrue();
    List<String> editPermissions = new ArrayList<>();
    permissionsAttr.path("permissions").path("edit").forEach(p -> editPermissions.add(p.asText()));
    assertThat(editPermissions).containsExactly("admin");
    assertThat(editPermissions).doesNotContain("user");
  }
}
