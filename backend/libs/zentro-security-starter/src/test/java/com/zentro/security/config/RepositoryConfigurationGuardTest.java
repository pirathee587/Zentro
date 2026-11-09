package com.zentro.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Repository-level, value-suppressing guards. Reads tracked worktree files only; never local env files. */
class RepositoryConfigurationGuardTest {
  static final Path ROOT = root();
  static final Pattern LIVE_FORMAT = Pattern.compile("(?<![A-Za-z0-9_-])zn_" + "live_[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])");
  static final Pattern PRIVATE_HEADER = Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----");
  static final List<String> SERVICES = List.of("identity-partner-service", "parcel-pricing-service", "dispatch-courier-service", "tracking-reporting-service", "notification-integration-service");
  static Path root() {
    Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (path != null && !Files.exists(path.resolve(".git"))) path = path.getParent();
    if (path == null) throw new IllegalStateException("Repository root required for configuration guards");
    return path;
  }
  static List<String> tracked() throws Exception {
    var process = new ProcessBuilder("git", "ls-files", "-z").directory(ROOT.toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    String paths = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) throw new IllegalStateException("Cannot enumerate tracked paths");
    return List.of(paths.split("\u0000"));
  }
  static String read(String path) throws Exception { return Files.readString(ROOT.resolve(path), StandardCharsets.ISO_8859_1); }
  static List<String> matches(String path, String data, Pattern pattern, String kind) {
    var result = new ArrayList<String>(); String[] lines = data.split("\\R", -1);
    for (int index = 0; index < lines.length; index++) {
      var matcher = pattern.matcher(lines[index]);
      while (matcher.find()) result.add(path + ":" + (index + 1) + " kind=" + kind + " length=" + matcher.group().length());
    }
    return result;
  }
  @Test void trackedFilesContainNoRealFormatApiKeyLiterals() throws Exception {
    var findings = new ArrayList<String>();
    for (String path : tracked()) if (Files.isRegularFile(ROOT.resolve(path))) findings.addAll(matches(path, read(path), LIVE_FORMAT, "API-key"));
    assertThat(findings).as("Credential metadata only; values suppressed").isEmpty();
  }
  @Test void trackedFilesContainNoPrivateKeyHeaders() throws Exception {
    var findings = new ArrayList<String>();
    for (String path : tracked()) if (Files.isRegularFile(ROOT.resolve(path))) findings.addAll(matches(path, read(path), PRIVATE_HEADER, "private-key-header"));
    assertThat(findings).as("Credential metadata only; values suppressed").isEmpty();
  }
  @Test void guardRejectsGeneratedRealFormatButAllowsClearlySyntheticExamples() {
    assertThat(LIVE_FORMAT.matcher("zn_" + "live_" + "A".repeat(43)).find()).isTrue();
    assertThat(LIVE_FORMAT.matcher("zn_example_xxx").find()).isFalse();
    assertThat(PRIVATE_HEADER.matcher("-----BEGIN " + "PRIVATE KEY-----").find()).isTrue();
    assertThat(PRIVATE_HEADER.matcher("PUBLIC KEY").find()).isFalse();
  }
  @Test void committedApplicationConfigurationHasNoLiteralSecretDefaults() throws Exception {
    var findings = new ArrayList<String>();
    var defaults = Pattern.compile("\\$\\{([A-Z0-9_]*(?:PASSWORD|SECRET|TOKEN)|[A-Z0-9_]*_KEY|KEY):([^}]*)}");
    for (String path : tracked()) {
      boolean application = path.matches(".*src/main/resources/application[^/]*\\.(?:yml|yaml|properties)");
      boolean source = path.contains("/src/main/java/") && path.endsWith(".java");
      if (!application && !source) continue;
      for (String line : read(path).split("\\R")) {
        var matcher = defaults.matcher(line);
        while (matcher.find()) {
          String value = matcher.group(2);
          if (!value.isEmpty() && !value.startsWith("${")) findings.add(path + " variable=" + matcher.group(1) + " default-length=" + value.length());
        }
        var property = Pattern.compile("\\$\\{([A-Za-z0-9_.-]*(?:password|secret|token|key)):([^}]*)}", Pattern.CASE_INSENSITIVE).matcher(line);
        while (property.find()) if (!property.group(2).isEmpty() && !property.group(2).startsWith("${"))
          findings.add(path + " property=" + property.group(1) + " default-length=" + property.group(2).length());
      }
    }
    assertThat(findings).as("Nonempty credential defaults; no production allow-list").isEmpty();
  }
  @Test void serviceDatasourcesRequireExplicitServiceScopedCredentials() throws Exception {
    for (String service : SERVICES) {
      String yaml = read("backend/" + service + "/src/main/resources/application.yml");
      assertThat(yaml.contains("url: ${DB_URL}")).as("%s datasource URL must be explicitly scoped by its service env", service).isTrue();
      assertThat(yaml.contains("username: ${DB_USER}")).as("%s runtime user must be explicitly scoped by its service env", service).isTrue();
      assertThat(yaml.contains("password: ${DB_PASSWORD}")).as("%s password reference must have no fallback", service).isTrue();
      assertThat(yaml.contains("KC_DB_")).as("%s must not inherit Keycloak database credentials", service).isFalse();
    }
  }
  @Test void managementConfigurationNeverExposesSensitiveDiagnosticEndpoints() throws Exception {
    var forbidden = Pattern.compile("(?m)^\\s*include:\\s*[^\\n]*(?:\\*|env|configprops|heapdump|threaddump|beans|mappings)");
    for (String path : tracked()) if (path.matches(".*src/main/resources/application[^/]*\\.yml"))
      assertThat(forbidden.matcher(read(path)).find()).as("%s sensitive actuator exposure", path).isFalse();
  }
  @Test void exampleCredentialValuesAreEmptyOrExplicitlyNonWorkingPlaceholders() throws Exception {
    var assignments = Pattern.compile("(?m)^([A-Z0-9_]*(?:PASSWORD|SECRET|TOKEN)|[A-Z0-9_]*_KEY|KEY)=(.*)$");
    var findings = new ArrayList<String>();
    for (String path : tracked()) if (path.endsWith(".env.example") || path.equals(".env.common.example")) {
      var matcher = assignments.matcher(read(path));
      while (matcher.find()) {
        String value = matcher.group(2).strip();
        if (!value.isEmpty() && !value.matches("(?i).*(?:replace|change|example|placeholder|your|base64|random|bytes|<|>|\\$\\{).*"))
          findings.add(path + " variable=" + matcher.group(1) + " length=" + value.length());
      }
    }
    assertThat(findings).as("Template metadata only; values suppressed").isEmpty();
  }
  @Test void gatewayCorsUsesConfiguredOriginsAndNoCredentialedWildcard() throws Exception {
    String java = read("backend/api-gateway/src/main/java/com/zentro/gateway/config/CorsConfig.java");
    assertThat(java.contains("${zentro.cors.allowed-origins}")).isTrue();
    assertThat(java.contains("setAllowCredentials(false)")).isTrue();
    assertThat(java.contains("setAllowedOriginPatterns")).isFalse();
    assertThat(read("backend/api-gateway/src/main/resources/application.yml").contains("CORS_ALLOWED_ORIGINS")).isTrue();
  }
  @Test void buildContextsExcludeLocalEnvironmentsKeysAndDatabaseBackups() throws Exception {
    for (String path : List.of(".dockerignore", "backend/.dockerignore", "frontend/zentro-web/.dockerignore")) {
      String ignore = read(path);
      for (String suffix : List.of(".env", "*.pem", "*.key", "*.p12", "*.jks", "*.dump", "*.backup", "*.pgdump"))
        assertThat(ignore.contains(suffix)).as("%s excludes %s", path, suffix).isTrue();
    }
  }
  @Test void explicitProductionProfilesExposeOnlyHealth() throws Exception {
    for (String service : List.of("api-gateway", "tracking-reporting-service")) {
      String yaml = read("backend/" + service + "/src/main/resources/application-prod.yml");
      assertThat(Pattern.compile("(?m)^\\s*include:\\s*health\\s*$").matcher(yaml).find()).as("%s production health exposure", service).isTrue();
      assertThat(yaml.contains("prometheus")).as("%s production metrics are not implicitly exposed", service).isFalse();
    }
  }
  @Test void developmentPublishedPortsHaveAConfigurableLoopbackDefault() throws Exception {
    int ports = 0;
    for (String path : List.of("docker-compose.yml", "backend/docker-compose.yml", "infra/keycloak/docker-compose.keycloak.yml")) {
      for (String line : read(path).split("\\R")) {
        if (!line.strip().matches("- \\\".*:[0-9]+\\\"")) continue;
        ports++;
        assertThat(line.contains("${BIND_ADDRESS:-127.0.0.1}:")).as("%s published port has an explicit safe binding", path).isTrue();
      }
    }
    assertThat(ports).isEqualTo(11);
  }
}
