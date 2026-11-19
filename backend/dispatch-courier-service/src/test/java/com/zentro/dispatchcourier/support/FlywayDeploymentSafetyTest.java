package com.zentro.dispatchcourier.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.Yaml;

/**
 * Regression guard: no S3 deployment path may let application startup run Flyway against
 * shared Supabase. Shared S3 records the historical acceptance V5 (checksum 1530139319); the
 * canonical db/migration V5 differs, so startup migration with validation bypassed would
 * silently apply V7. Shared migrations run only through the guarded runbook.
 */
class FlywayDeploymentSafetyTest {

  private static final String S3 = "dispatch-courier-service";
  private static final Path REPO = repositoryRoot();
  private static final Path MODULE = REPO.resolve("backend/" + S3);

  @Test
  void applicationDefaultsDisableFlywayWithValidationOn() throws IOException {
    FlywayProperties flyway = bind(Map.of());
    assertThat(flyway.isEnabled()).as("spring.flyway.enabled default").isFalse();
    assertThat(flyway.isValidateOnMigrate()).as("spring.flyway.validate-on-migrate default").isTrue();
  }

  @Test
  void envFileValidationBypassAloneCannotEnableFlyway() throws IOException {
    FlywayProperties flyway = bind(Map.of("SPRING_FLYWAY_VALIDATE_ON_MIGRATE", "false"));
    assertThat(flyway.isEnabled()).isFalse();
  }

  @Test
  void composeS3EnvironmentOverridesUnsafeEnvFileValues() throws IOException {
    Map<String, String> composeEnv = composeEnvironment("docker-compose.yml");
    assertThat(composeEnv).containsEntry("SPRING_FLYWAY_ENABLED", "false")
        .containsEntry("SPRING_FLYWAY_VALIDATE_ON_MIGRATE", "true");

    // Compose `environment:` wins over `env_file:`. Simulate the worst-case env_file.
    Map<String, String> effective = new HashMap<>(Map.of(
        "SPRING_FLYWAY_ENABLED", "true", "SPRING_FLYWAY_VALIDATE_ON_MIGRATE", "false"));
    effective.putAll(composeEnv);
    FlywayProperties flyway = bind(effective);
    assertThat(flyway.isEnabled()).isFalse();
    assertThat(flyway.isValidateOnMigrate()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void everySharedComposeFileThatDefinesS3CarriesTheFlywayGuard() throws IOException {
    for (String file : List.of("docker-compose.yml", "backend/docker-compose.yml")) {
      Path path = REPO.resolve(file);
      if (!Files.isRegularFile(path)) continue;
      Map<String, Object> services = (Map<String, Object>) yaml(path).get("services");
      if (services == null || !services.containsKey(S3)) continue;
      assertThat(composeEnvironment(file)).as(file)
          .containsEntry("SPRING_FLYWAY_ENABLED", "false")
          .containsEntry("SPRING_FLYWAY_VALIDATE_ON_MIGRATE", "true");
    }
  }

  @Test
  void localDatabaseOverrideEnablesFlywayOnlyForIsolatedLocalPostgres() throws IOException {
    Map<String, String> override = composeEnvironment("docker-compose.local-db.yml");
    if ("true".equalsIgnoreCase(override.get("SPRING_FLYWAY_ENABLED"))) {
      assertThat(override.get("DB_URL")).startsWith("jdbc:postgresql://local-postgres:");
      assertThat(override.getOrDefault("SPRING_FLYWAY_VALIDATE_ON_MIGRATE", "true")).isEqualToIgnoringCase("true");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void helmS3EnvironmentDisablesFlywayAndTemplateRendersIt() throws IOException {
    Map<String, Object> values = yaml(REPO.resolve("infra/helm/zentro/values.yaml"));
    Map<String, Object> s3 = (Map<String, Object>) ((Map<String, Object>) values.get("services")).get(S3);
    Map<String, Object> env = (Map<String, Object>) s3.get("env");
    assertThat(env).as("Helm services.%s.env", S3).isNotNull();
    assertThat(String.valueOf(env.get("SPRING_FLYWAY_ENABLED"))).isEqualTo("false");
    assertThat(String.valueOf(env.get("SPRING_FLYWAY_VALIDATE_ON_MIGRATE"))).isEqualTo("true");

    String template = read(REPO.resolve("infra/helm/zentro/templates/services.yaml"));
    int envFrom = template.indexOf("envFrom:");
    int explicitEnv = template.indexOf("{{- with $service.env }}");
    assertThat(envFrom).isPositive();
    assertThat(explicitEnv).as("template renders per-service env after envFrom").isGreaterThan(envFrom);
  }

  @Test
  void environmentTemplateKeepsFlywayDisabled() throws IOException {
    Map<String, String> example = dotenv(MODULE.resolve(".env.example"));
    assertThat(example.getOrDefault("SPRING_FLYWAY_ENABLED", "false")).isEqualToIgnoringCase("false");
    assertThat(example.getOrDefault("SPRING_FLYWAY_VALIDATE_ON_MIGRATE", "true")).isEqualToIgnoringCase("true");
  }

  @Test
  void localSharedConnectedEnvFileDoesNotEnableFlyway() throws IOException {
    Path env = MODULE.resolve(".env");
    if (!Files.isRegularFile(env)) return; // ignored local file; absent in CI
    Map<String, String> values = dotenv(env);
    boolean shared = values.getOrDefault("DB_URL", "").contains("supabase.com");
    if (shared) {
      // Assert without echoing any value from the file.
      assertThat("true".equalsIgnoreCase(values.get("SPRING_FLYWAY_ENABLED")))
          .as("shared-connected S3 .env must not set SPRING_FLYWAY_ENABLED=true").isFalse();
    }
  }

  @Test
  void noDeploymentPathEnablesS3FlywayOutsideApprovedDisposableContexts() throws IOException {
    Pattern enable = Pattern.compile("(?i)(SPRING_FLYWAY_ENABLED\\s*[:=]\\s*\"?true|spring\\.flyway\\.enabled\\s*[:=]\\s*\"?true)");
    Pattern migrate = Pattern.compile("(?i)(flyway(:|\\s+)migrate|flyway-maven-plugin)");
    List<String> allowed = List.of(
        "docker-compose.local-db.yml", // isolated local-postgres, checked above
        "backend/dispatch-courier-service/src/test/resources/application.properties", // Testcontainers
        "backend/merchant-order-webhook-e2e/src/test/java/com/zentro/e2e/DisposableGatewayStack.java", // guarded disposable DB
        "backend/merchant-order-webhook-e2e/src/test/java/com/zentro/e2e/CourierLocalIntegrationStack.java", // guarded disposable DB
        "backend/dispatch-courier-service/src/test/java/com/zentro/dispatchcourier/support/FlywayDeploymentSafetyTest.java");
    List<Path> candidates = new ArrayList<>();
    candidates.add(REPO.resolve("docker-compose.yml"));
    candidates.add(REPO.resolve("docker-compose.local-db.yml"));
    candidates.add(MODULE.resolve("Dockerfile"));
    candidates.add(MODULE.resolve(".env.example"));
    candidates.add(MODULE.resolve("pom.xml"));
    candidates.add(MODULE.resolve("src/main/resources/application.yml"));
    candidates.addAll(files(REPO.resolve(".github/workflows")));
    candidates.addAll(files(REPO.resolve("infra/helm")));
    candidates.addAll(files(REPO.resolve("infra/k8s")));
    candidates.addAll(files(REPO.resolve("scripts")));
    candidates.addAll(files(REPO.resolve("backend/merchant-order-webhook-e2e/src")));

    List<String> violations = new ArrayList<>();
    for (Path file : candidates) {
      if (!Files.isRegularFile(file)) continue;
      String relative = REPO.relativize(file).toString().replace('\\', '/');
      String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).lines()
          .filter(line -> !line.trim().startsWith("#") && !line.trim().startsWith("//"))
          .reduce("", (a, b) -> a + "\n" + b);
      if (enable.matcher(text).find() && !allowed.contains(relative)) violations.add(relative + ": enables Flyway");
      if (migrate.matcher(text).find()) violations.add(relative + ": runs a Flyway migrate command/plugin");
    }
    assertThat(violations).isEmpty();
  }

  // --- helpers ---------------------------------------------------------------------------

  /** Binds FlywayProperties from S3's main application.yml with OS-environment overrides on top. */
  private static FlywayProperties bind(Map<String, String> osEnvironment) throws IOException {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(new SystemEnvironmentPropertySource(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(osEnvironment)));
    for (PropertySource<?> yml : new YamlPropertySourceLoader().load("application.yml",
        new FileSystemResource(MODULE.resolve("src/main/resources/application.yml")))) {
      sources.addLast(yml);
    }
    return new Binder(ConfigurationPropertySources.from(sources))
        .bindOrCreate("spring.flyway", FlywayProperties.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> composeEnvironment(String file) throws IOException {
    Map<String, Object> services = (Map<String, Object>) yaml(REPO.resolve(file)).get("services");
    Map<String, Object> service = (Map<String, Object>) services.get(S3);
    assertThat(service).as("%s defines %s", file, S3).isNotNull();
    Object env = service.get("environment");
    Map<String, String> result = new LinkedHashMap<>();
    if (env instanceof Map<?, ?> map) map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
    if (env instanceof List<?> list) list.forEach(entry -> {
      String[] kv = String.valueOf(entry).split("=", 2);
      result.put(kv[0], kv.length > 1 ? kv[1] : "");
    });
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> yaml(Path file) throws IOException {
    return (Map<String, Object>) new Yaml().load(read(file));
  }

  private static Map<String, String> dotenv(Path file) throws IOException {
    Map<String, String> result = new HashMap<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      String trimmed = line.trim();
      int eq = trimmed.indexOf('=');
      if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) continue;
      result.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
    }
    return result;
  }

  private static List<Path> files(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) return List.of();
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> !p.toString().contains("node_modules") && !p.toString().contains(java.io.File.separator + "target" + java.io.File.separator)
              && !p.toString().contains("__pycache__"))
          .toList();
    }
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  private static Path repositoryRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !(Files.isRegularFile(dir.resolve("docker-compose.yml")) && Files.isDirectory(dir.resolve(".github"))))
      dir = dir.getParent();
    if (dir == null) throw new IllegalStateException("repository root (docker-compose.yml + .github) not found");
    return dir;
  }
}
