package com.zentro.dispatchcourier.courier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Courier identity client backed by S1 {@link IdentityPartnerGrpcClient} over gRPC.
 */
@Component
public class IdentityCourierClient {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final IdentityPartnerGrpcClient grpcClient;

  public IdentityCourierClient(IdentityPartnerGrpcClient grpcClient) {
    this.grpcClient = grpcClient;
  }

  public Courier currentCourier(String bearerToken) {
    String subject = resolveSubject(bearerToken);
    return currentCourierBySubject(subject);
  }

  public Courier currentCourierBySubject(String subject) {
    Courier courier = grpcClient.getCourierByUserSubject(subject);
    if (!isEligible(courier)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Courier account is inactive or suspended");
    }
    return courier;
  }

  public List<Courier> listEligible(String bearerToken) {
    return grpcClient.listCouriers(true).stream()
        .filter(IdentityCourierClient::isEligible)
        .toList();
  }

  public List<Courier> listAll(String bearerToken) {
    return grpcClient.listCouriers(null);
  }

  public Courier requireEligible(UUID courierId, String bearerToken) {
    Courier courier = grpcClient.getCourierById(courierId);
    if (!isEligible(courier)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Courier is inactive or suspended");
    }
    return courier;
  }

  private static boolean isEligible(Courier courier) {
    return courier != null
        && courier.id() != null
        && courier.userId() != null
        && Boolean.TRUE.equals(courier.active())
        && "ACTIVE".equalsIgnoreCase(courier.accountStatus());
  }

  private static String resolveSubject(String bearerToken) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
      if (bearerToken == null || bearerToken.isBlank() || bearerToken.equals(jwt.getTokenValue())) {
        if (jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
          return jwt.getSubject();
        }
      }
    }
    if (bearerToken == null || bearerToken.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing courier authentication token");
    }
    String[] parts = bearerToken.split("\\.");
    if (parts.length < 2) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid courier authentication token");
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
      JsonNode payload = JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
      String sub = payload.path("sub").asText("");
      if (sub.isBlank()) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing subject claim in courier token");
      }
      return sub;
    } catch (IllegalArgumentException | java.io.IOException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Malformed courier authentication token", e);
    }
  }

  public record Courier(
      UUID id,
      UUID userId,
      String email,
      String name,
      String phone,
      Boolean active,
      String accountStatus,
      String assignedZone,
      String vehicleType) {
    public Courier(UUID id, UUID userId, String name, Boolean active, String accountStatus) {
      this(id, userId, null, name, null, active, accountStatus, null, null);
    }

    public Courier(UUID id, UUID userId, String name, Boolean active, String accountStatus, String assignedZone) {
      this(id, userId, null, name, null, active, accountStatus, assignedZone, null);
    }
  }
}
