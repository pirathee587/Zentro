package com.zentro.trackingreporting.tracking.service;

import com.zentro.trackingreporting.security.DeliveryAuthorizationService;
import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class TrackingStreamService {

  private static final Logger log = LoggerFactory.getLogger(TrackingStreamService.class);
  public static final long SSE_TIMEOUT_MS = 300_000L; // 5 minutes

  public static class StreamSubscriber {
    private final String id;
    private final SseEmitter emitter;
    private final String trackingRef;
    private final String userSubject;
    private final Set<String> roles;
    private final Instant tokenExpiresAt;

    public StreamSubscriber(
        String id,
        SseEmitter emitter,
        String trackingRef,
        String userSubject,
        Set<String> roles,
        Instant tokenExpiresAt) {
      this.id = id;
      this.emitter = emitter;
      this.trackingRef = trackingRef;
      this.userSubject = userSubject;
      this.roles = roles;
      this.tokenExpiresAt = tokenExpiresAt;
    }

    public String getId() { return id; }
    public SseEmitter getEmitter() { return emitter; }
    public String getTrackingRef() { return trackingRef; }
    public String getUserSubject() { return userSubject; }
    public Set<String> getRoles() { return roles; }
    public Instant getTokenExpiresAt() { return tokenExpiresAt; }

    public boolean isTokenExpired() {
      return tokenExpiresAt != null && Instant.now().isAfter(tokenExpiresAt);
    }
  }

  private final Map<String, List<StreamSubscriber>> subscribers = new ConcurrentHashMap<>();
  private final DeliveryAuthorizationService authorizationService;
  private final TrackingParcelRepository parcelRepository;

  public TrackingStreamService(
      DeliveryAuthorizationService authorizationService,
      TrackingParcelRepository parcelRepository) {
    this.authorizationService = authorizationService;
    this.parcelRepository = parcelRepository;
  }

  /**
   * Subscribes an authenticated, authorized client to live tracking updates for a delivery.
   *
   * @param trackingRef the tracking reference
   * @param authentication the caller's authentication (must contain valid, non-expired JWT)
   * @param parcel the parcel to subscribe to
   * @return initialized SseEmitter
   */
  public SseEmitter subscribe(String trackingRef, Authentication authentication, TrackingParcel parcel) {
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required to subscribe to live updates");
    }

    Instant expiresAt = extractTokenExpiration(authentication);
    if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access token has expired");
    }

    if (parcel == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found");
    }

    if (!authorizationService.isAuthorized(authentication, parcel)) {
      log.warn("Access denied: subject {} cannot subscribe to tracking for {}",
          authorizationService.extractSubject(authentication), trackingRef);
      throw new AccessDeniedException("Access denied to delivery live tracking updates");
    }

    String key = trackingRef.trim().toUpperCase();
    String subscriberId = UUID.randomUUID().toString();
    String subject = authorizationService.extractSubject(authentication);
    Set<String> roles = authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .collect(Collectors.toSet());

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    StreamSubscriber subscriber = new StreamSubscriber(
        subscriberId, emitter, key, subject, roles, expiresAt);

    subscribers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(subscriber);

    emitter.onCompletion(() -> removeSubscriber(key, subscriberId));
    emitter.onTimeout(() -> removeSubscriber(key, subscriberId));
    emitter.onError(ex -> removeSubscriber(key, subscriberId));

    // Send safe initial connection event
    try {
      emitter.send(SseEmitter.event()
          .name("connected")
          .data(Map.of("trackingRef", key, "status", "CONNECTED")));
    } catch (IOException e) {
      log.debug("Failed to send initial SSE connected event for subscriber {} on {}", subscriberId, key, e);
      removeSubscriber(key, subscriberId);
    }

    return emitter;
  }

  /**
   * Broadcasts safe live tracking events to authorized, active subscribers.
   * Revalidates token expiration and delivery authorization before delivering each event.
   */
  public void notifySubscribers(TrackingEvent event) {
    if (event == null || event.getTrackingRef() == null) return;
    String key = event.getTrackingRef().trim().toUpperCase();
    List<StreamSubscriber> list = subscribers.get(key);
    if (list == null || list.isEmpty()) return;

    Optional<TrackingParcel> currentParcel = parcelRepository.findByTrackingRef(key);

    for (StreamSubscriber subscriber : list) {
      // 1. Revalidate token expiration
      if (subscriber.isTokenExpired()) {
        log.info("Closing SSE emitter for subject {} on {} due to token expiration",
            subscriber.getUserSubject(), key);
        try {
          subscriber.getEmitter().complete();
        } catch (Exception ignored) {}
        removeSubscriber(key, subscriber.getId());
        continue;
      }

      // 2. Revalidate parcel ownership / authorization
      if (currentParcel.isPresent()) {
        if (!authorizationService.isAuthorized(subscriber.getUserSubject(), subscriber.getRoles(), currentParcel.get())) {
          log.warn("Revoking SSE subscription for subject {} on {} due to authorization change",
              subscriber.getUserSubject(), key);
          try {
            subscriber.getEmitter().complete();
          } catch (Exception ignored) {}
          removeSubscriber(key, subscriber.getId());
          continue;
        }
      }

      // 3. Send strictly safe tracking payload (no customer PII, addresses, or fees)
      try {
        subscriber.getEmitter().send(SseEmitter.event()
            .name("tracking-update")
            .data(Map.of(
                "trackingRef", event.getTrackingRef(),
                "status", event.getStatus().name(),
                "title", event.getTitle() != null ? event.getTitle() : event.getStatus().defaultTitle(),
                "description", event.getDescription() != null ? event.getDescription() : "",
                "occurredAt", event.getOccurredAt() != null ? event.getOccurredAt().toString() : Instant.now().toString()
            )));
      } catch (IOException e) {
        log.debug("Removing dead SSE emitter {} for {}", subscriber.getId(), key);
        removeSubscriber(key, subscriber.getId());
      }
    }
  }

  /**
   * Periodic cleanup of expired or dead subscribers (every 30 seconds).
   */
  @Scheduled(fixedDelay = 30_000)
  public void cleanupExpiredSubscribers() {
    for (Map.Entry<String, List<StreamSubscriber>> entry : subscribers.entrySet()) {
      String key = entry.getKey();
      for (StreamSubscriber sub : entry.getValue()) {
        if (sub.isTokenExpired()) {
          log.debug("Cleaning up expired subscriber {} on {}", sub.getId(), key);
          try {
            sub.getEmitter().complete();
          } catch (Exception ignored) {}
          removeSubscriber(key, sub.getId());
        }
      }
    }
  }

  public int getActiveSubscriberCount(String trackingRef) {
    List<StreamSubscriber> list = subscribers.get(trackingRef.trim().toUpperCase());
    return list != null ? list.size() : 0;
  }

  private void removeSubscriber(String trackingRef, String subscriberId) {
    List<StreamSubscriber> list = subscribers.get(trackingRef);
    if (list != null) {
      list.removeIf(sub -> Objects.equals(sub.getId(), subscriberId));
      if (list.isEmpty()) {
        subscribers.remove(trackingRef);
      }
    }
  }

  private Instant extractTokenExpiration(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken jwtAuth) {
      return jwtAuth.getToken().getExpiresAt();
    }
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      return jwt.getExpiresAt();
    }
    return null;
  }
}
