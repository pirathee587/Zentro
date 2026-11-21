package com.zentro.security.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architecture enforcement test: prohibits introducing new internal synchronous REST clients
 * (RestClient, RestTemplate, WebClient) across backend microservices.
 * Legitimate external HTTP integrations and explicitly tracked transitional clients are allowlisted.
 */
class ArchitectureGrpcComplianceTest {
  private static final Pattern HTTP_CLIENT_PATTERN =
      Pattern.compile("\\b(RestClient|RestTemplate|WebClient)\\b");

  /**
   * Approved external HTTP clients and shared starter utilities. Any NEW file using HTTP clients
   * for internal service calls will fail CI.
   */
  private static final Set<String> ALLOWLIST = Set.of(
      "backend/libs/zentro-security-starter/src/main/java/com/zentro/security/config/CorrelationAutoConfiguration.java",
      "backend/libs/zentro-security-starter/src/main/java/com/zentro/security/grpc/GrpcServiceTokenProvider.java",
      "backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/assistant/service/DeepSeekAssistant.java",
      "backend/dispatch-courier-service/src/main/java/com/zentro/dispatchcourier/courier/service/ServiceTokenProvider.java",
      // Transitional courier coordination / dashboard HTTP clients merged from main (PR #393)
      "backend/dispatch-courier-service/src/main/java/com/zentro/dispatchcourier/dashboard/CourierIdentityReadClient.java",
      "backend/dispatch-courier-service/src/main/java/com/zentro/dispatchcourier/dispatch/coordination/ParcelClaimClient.java",
      "backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/assignment/AssignmentOutcomeClient.java",
      "backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/graphql/dashboard/CourierAssignmentSnapshotClient.java"
  );

  @Test
  void noUnauthorizedInternalRestClientsExist() throws IOException {
    Path backendRoot = locateBackendRoot();
    List<String> violations;
    try (Stream<Path> files = Files.walk(backendRoot)) {
      violations = files
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .filter(p -> p.toString().contains("/src/main/java/"))
          .filter(p -> !p.toString().contains("/target/"))
          .filter(p -> containsHttpClient(p))
          .map(p -> "backend/" + backendRoot.relativize(p).toString().replace('\\', '/'))
          .filter(rel -> !ALLOWLIST.contains(rel))
          .sorted()
          .toList();
    }
    assertThat(violations)
        .as("Prohibited internal REST clients found. Use gRPC instead of RestClient/RestTemplate/WebClient.")
        .isEmpty();
  }

  private static boolean containsHttpClient(Path file) {
    try {
      return HTTP_CLIENT_PATTERN.matcher(Files.readString(file)).find();
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read " + file, ex);
    }
  }

  private static Path locateBackendRoot() {
    Path cwd = Paths.get("").toAbsolutePath();
    if (Files.isDirectory(cwd.resolve("libs/zentro-security-starter"))) {
      return cwd;
    }
    Path parent = cwd.getParent();
    while (parent != null) {
      if (Files.isDirectory(parent.resolve("libs/zentro-security-starter"))) {
        return parent;
      }
      parent = parent.getParent();
    }
    throw new IllegalStateException("Could not locate backend root from " + cwd);
  }
}
