package com.zentro.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;

/** Actual service YAML placeholder resolution, with no local env imports or database connection. */
class ServiceDatasourceConfigurationTest {
  static Stream<Arguments> missing() {
    return Stream.of("identity-partner-service", "tracking-reporting-service").flatMap(service ->
        Stream.of("DB_URL", "DB_USER", "DB_PASSWORD").map(variable -> Arguments.of(service, variable)));
  }
  static Stream<String> services() { return Stream.of("identity-partner-service", "tracking-reporting-service"); }
  ApplicationContextRunner runner(String service, String absent) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("DB_URL", "jdbc:postgresql://synthetic.invalid:5432/synthetic_database");
    values.put("DB_USER", "synthetic_runtime");
    values.put("DB_PASSWORD", "synthetic-nonworking-password");
    values.remove(absent);
    return new ApplicationContextRunner().withUserConfiguration(DatabaseContract.class)
        .withPropertyValues(values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new))
        .withInitializer(context -> {
          context.getEnvironment().getPropertySources().remove("systemEnvironment");
          context.getEnvironment().getPropertySources().remove("systemProperties");
          try {
            var file = RepositoryConfigurationGuardTest.ROOT.resolve("backend/" + service + "/src/main/resources/application.yml");
            for (var source : new YamlPropertySourceLoader().load(service, new FileSystemResource(file)))
              context.getEnvironment().getPropertySources().addLast(source);
          } catch (Exception error) { throw new IllegalStateException("Cannot load service configuration (values suppressed)"); }
        });
  }
  @ParameterizedTest @MethodSource("missing")
  void missingExplicitDatabasePropertyFailsConfigurationResolution(String service, String variable) {
    runner(service, variable).run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
      // Assert only the variable name; never print a property source or credential value.
      Throwable cause = context.getStartupFailure();
      while (cause.getCause() != null) cause = cause.getCause();
      assertThat(cause.getMessage().contains(variable)).as("Missing variable name is identified").isTrue();
    });
  }
  @ParameterizedTest @MethodSource("services")
  void allExplicitDatabasePropertiesResolveWithoutFallback(String service) {
    runner(service, "unused").run(context -> assertThat(context).hasNotFailed().hasSingleBean(DatabaseSettings.class));
  }
  record DatabaseSettings(String url, String user, String password) {
    @Override public String toString() { return "DatabaseSettings[redacted]"; }
  }
  @Configuration(proxyBeanMethods = false)
  static class DatabaseContract {
    @Bean DatabaseSettings settings(Environment env) {
      return new DatabaseSettings(env.getRequiredProperty("spring.datasource.url"), env.getRequiredProperty("spring.datasource.username"), env.getRequiredProperty("spring.datasource.password"));
    }
  }
}
