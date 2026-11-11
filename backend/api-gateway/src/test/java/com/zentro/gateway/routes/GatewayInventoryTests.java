package com.zentro.gateway.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

/** Source audit deliberately fails closed if the current simple mapping conventions change. */
class GatewayInventoryTests {
  private static final List<String> SERVICES = List.of("identity-partner-service", "parcel-pricing-service",
      "dispatch-courier-service", "tracking-reporting-service", "notification-integration-service");
  private static final List<String> IDS = List.of("identity", "parcel", "dispatch", "tracking", "notification");
  private static final Map<String, String> RESERVED = Map.of(
      "/api/v1/admin/zones/**", "Reserved S2 zone management; no controller",
      "/api/v1/reports/**", "Reserved S4 reports; no controller",
      "/api/v1/admin/audit-logs/**", "Reserved S5 audit logs; no controller",
      "/graphql", "Reserved S4 GraphQL read-only tracking endpoint; handled by TrackingGraphQlEndpoint");

  static StandardEnvironment configuration() throws Exception {
    StandardEnvironment environment = new StandardEnvironment();
    for (var source : new YamlPropertySourceLoader().load("gateway-audit", new ClassPathResource("application.yml"))) {
      environment.getPropertySources().addLast(source);
    }
    return environment;
  }

  static List<String> paths(RouteDefinition route) {
    assertThat(route.getPredicates()).hasSize(1);
    assertThat(route.getPredicates().getFirst().getName()).isEqualTo("Path");
    return new ArrayList<>(route.getPredicates().getFirst().getArgs().values());
  }

  private static List<String> quotedPaths(String text) {
    List<String> paths = new ArrayList<>();
    var matcher = Pattern.compile("\"(/[^\"]*)\"").matcher(text);
    while (matcher.find()) paths.add(matcher.group(1));
    return paths;
  }

  @Test
  void activeControllerMappingsHaveExactlyOneEffectiveOwnerAndReservedPrefixesAreExplicit() throws Exception {
    var routes = Binder.get(configuration()).bind("spring.cloud.gateway.server.webflux", GatewayProperties.class).get().getRoutes();
    assertThat(routes).extracting(RouteDefinition::getId).containsExactlyElementsOf(IDS);
    List<String> inventory = new ArrayList<>(List.of("| Service | Method | Path | Effective route | Source |",
        "|---|---|---|---|---|"));
    var parser = new PathPatternParser();
    List<String> usedPatterns = new ArrayList<>();
    for (int serviceIndex = 0; serviceIndex < SERVICES.size(); serviceIndex++) {
      String service = SERVICES.get(serviceIndex);
      try (var sources = Files.walk(Path.of("..", service, "src/main/java"))) {
        for (Path source : sources.filter(p -> p.toString().endsWith("Controller.java")).sorted().toList()) {
          String java = Files.readString(source);
          assertThat(java).as("explicit controller stereotype: %s", source).contains("@RestController");
          var base = Pattern.compile("@RequestMapping\\(([^)]*)\\)").matcher(java);
          assertThat(base.find()).as("class mapping: %s", source).isTrue();
          List<String> bases = quotedPaths(base.group(1));
          assertThat(bases).isNotEmpty();
          var methods = Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping(?:\\s*\\(([^)]*)\\))?").matcher(java);
          int count = 0;
          while (methods.find()) {
            count++;
            List<String> suffixes = quotedPaths(methods.group(2) == null ? "" : methods.group(2));
            if (suffixes.isEmpty()) suffixes = List.of("");
            for (String prefix : bases) for (String suffix : suffixes) {
              String path = prefix + suffix;
              var matching = routes.stream().filter(r -> paths(r).stream().anyMatch(p -> parser.parse(p)
                  .matches(PathContainer.parsePath(path)))).toList();
              assertThat(matching).as("route for %s", path).isNotEmpty();
              assertThat(matching.getFirst().getId()).as("effective owner for %s", path).isEqualTo(IDS.get(serviceIndex));
              // The sole intentional overlap: ordered S1 profile prefix before general S3 courier prefix.
              if (matching.size() > 1) {
                assertThat(path).startsWith("/api/v1/courier/profile");
                assertThat(matching).extracting(RouteDefinition::getId).containsExactly("identity", "dispatch");
              }
              paths(matching.getFirst()).stream().filter(p -> parser.parse(p).matches(PathContainer.parsePath(path)))
                  .forEach(usedPatterns::add);
              inventory.add("| " + service + " | " + methods.group(1).toUpperCase() + " | `" + path + "` | "
                  + matching.getFirst().getId() + " | " + source.getFileName() + " | ");
            }
          }
          assertThat(count).as("method mappings in %s", source).isPositive();
        }
      }
    }
    for (var route : routes) {
      assertThat(route.getFilters()).isEmpty();
      for (String path : paths(route)) assertThat(usedPatterns.contains(path) || RESERVED.containsKey(path))
          .as("unexplained route prefix %s", path).isTrue();
    }
    Files.createDirectories(Path.of("target"));
    Files.write(Path.of("target/gateway-controller-inventory.md"), inventory);
    assertThat(inventory.size()).isGreaterThan(50);
  }

  @Test
  void timeoutDefaultsAndOverridesAreBoundFromRealConfiguration() throws Exception {
    var environment = configuration();
    var defaults = Binder.get(environment).bind("spring.cloud.gateway.server.webflux.httpclient", HttpClientProperties.class).get();
    assertThat(defaults.getConnectTimeout()).isEqualTo(1000);
    assertThat(defaults.getResponseTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
    environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("audit-overrides",
        Map.of("GATEWAY_CONNECT_TIMEOUT_MS", "321", "GATEWAY_RESPONSE_TIMEOUT", "789ms",
            "GATEWAY_TRACKING_RESPONSE_TIMEOUT_MS", "987")));
    var overridden = Binder.get(environment).bind("spring.cloud.gateway.server.webflux.httpclient", HttpClientProperties.class).get();
    assertThat(overridden.getConnectTimeout()).isEqualTo(321);
    assertThat(overridden.getResponseTimeout()).isEqualTo(java.time.Duration.ofMillis(789));
    var routes = Binder.get(environment).bind("spring.cloud.gateway.server.webflux.routes", Bindable.listOf(RouteDefinition.class)).get();
    assertThat(routes.stream().filter(r -> r.getId().equals("tracking")).findFirst().orElseThrow()
        .getMetadata().get("response-timeout")).isEqualTo("987");
  }
}
