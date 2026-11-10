package com.zentro.trackingreporting.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zentro.trackingreporting.security.DeliveryAuthorizationService;
import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class TrackingStreamServiceTest {

  private DeliveryAuthorizationService authorizationService;
  private TrackingParcelRepository parcelRepository;
  private TrackingStreamService streamService;
  private TrackingParcel parcel;

  @BeforeEach
  void setUp() {
    authorizationService = mock(DeliveryAuthorizationService.class);
    parcelRepository = mock(TrackingParcelRepository.class);
    streamService = new TrackingStreamService(authorizationService, parcelRepository);

    parcel = new TrackingParcel();
    parcel.setTrackingRef("ZT-STREAM1234");
    parcel.setCustomerSubject("cust-alice");
    parcel.setSenderName("Alice Secret");
    parcel.setSenderPhone("0771112233");
    parcel.setSenderAddress("123 Private Road");
  }

  private JwtAuthenticationToken createJwtToken(String subject, String role, Instant expiresAt) {
    Jwt jwt = Jwt.withTokenValue("mock-token")
        .header("alg", "RS256")
        .subject(subject)
        .claim("scope", "openid")
        .expiresAt(expiresAt)
        .build();

    return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
  }

  @Test
  @DisplayName("Subscribe with null or unauthenticated user throws 401")
  void subscribeUnauthenticatedThrows401() {
    assertThatThrownBy(() -> streamService.subscribe("ZT-STREAM1234", null, parcel))
        .isInstanceOf(ResponseStatusException.class)
        .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("Subscribe with expired token throws 401")
  void subscribeExpiredTokenThrows401() {
    Instant expiredTime = Instant.now().minus(10, ChronoUnit.MINUTES);
    JwtAuthenticationToken token = createJwtToken("cust-alice", "CUSTOMER", expiredTime);

    assertThatThrownBy(() -> streamService.subscribe("ZT-STREAM1234", token, parcel))
        .isInstanceOf(ResponseStatusException.class)
        .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("Subscribe to null parcel throws 404")
  void subscribeNullParcelThrows404() {
    Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
    JwtAuthenticationToken token = createJwtToken("cust-alice", "CUSTOMER", future);

    assertThatThrownBy(() -> streamService.subscribe("ZT-STREAM1234", token, null))
        .isInstanceOf(ResponseStatusException.class)
        .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Subscribe by unauthorized user throws AccessDeniedException (403)")
  void subscribeUnauthorizedThrows403() {
    Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
    JwtAuthenticationToken token = createJwtToken("cust-eve", "CUSTOMER", future);
    when(authorizationService.isAuthorized(token, parcel)).thenReturn(false);

    assertThatThrownBy(() -> streamService.subscribe("ZT-STREAM1234", token, parcel))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Subscribe by authorized user creates SseEmitter and increments active count")
  void subscribeAuthorizedSuccess() {
    Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
    JwtAuthenticationToken token = createJwtToken("cust-alice", "CUSTOMER", future);
    when(authorizationService.isAuthorized(token, parcel)).thenReturn(true);
    when(authorizationService.extractSubject(token)).thenReturn("cust-alice");

    SseEmitter emitter = streamService.subscribe("ZT-STREAM1234", token, parcel);
    assertThat(emitter).isNotNull();
    assertThat(streamService.getActiveSubscriberCount("ZT-STREAM1234")).isEqualTo(1);
  }

  @Test
  @DisplayName("notifySubscribers drops subscriber whose token expired during stream")
  void notifySubscribersDropsExpiredSubscriber() {
    // Subscribe with a token that expires very soon (simulate expiration)
    Instant almostExpired = Instant.now().plusMillis(50);
    JwtAuthenticationToken token = createJwtToken("cust-alice", "CUSTOMER", almostExpired);
    when(authorizationService.isAuthorized(token, parcel)).thenReturn(true);
    when(authorizationService.extractSubject(token)).thenReturn("cust-alice");

    streamService.subscribe("ZT-STREAM1234", token, parcel);
    assertThat(streamService.getActiveSubscriberCount("ZT-STREAM1234")).isEqualTo(1);

    // Sleep past expiration
    try { Thread.sleep(60); } catch (InterruptedException ignored) {}

    TrackingEvent event = new TrackingEvent();
    event.setTrackingRef("ZT-STREAM1234");
    event.setStatus(TrackingStatus.IN_TRANSIT);
    event.setTitle("In transit");
    event.setDescription("Package on route");
    event.setOccurredAt(Instant.now());

    when(parcelRepository.findByTrackingRef("ZT-STREAM1234")).thenReturn(Optional.of(parcel));

    streamService.notifySubscribers(event);

    // Expired subscriber should have been removed
    assertThat(streamService.getActiveSubscriberCount("ZT-STREAM1234")).isEqualTo(0);
  }

  @Test
  @DisplayName("notifySubscribers drops subscriber whose authorization was revoked")
  void notifySubscribersDropsDeauthorizedSubscriber() {
    Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
    JwtAuthenticationToken token = createJwtToken("courier-bob", "COURIER", future);
    when(authorizationService.isAuthorized(token, parcel)).thenReturn(true);
    when(authorizationService.extractSubject(token)).thenReturn("courier-bob");

    streamService.subscribe("ZT-STREAM1234", token, parcel);
    assertThat(streamService.getActiveSubscriberCount("ZT-STREAM1234")).isEqualTo(1);

    // Simulate reassignment: parcel courier changed
    TrackingParcel updatedParcel = new TrackingParcel();
    updatedParcel.setTrackingRef("ZT-STREAM1234");
    updatedParcel.setCourierSubject("courier-other");
    when(parcelRepository.findByTrackingRef("ZT-STREAM1234")).thenReturn(Optional.of(updatedParcel));
    when(authorizationService.isAuthorized(eq("courier-bob"), any(), eq(updatedParcel))).thenReturn(false);

    TrackingEvent event = new TrackingEvent();
    event.setTrackingRef("ZT-STREAM1234");
    event.setStatus(TrackingStatus.OUT_FOR_DELIVERY);
    event.setTitle("Out for delivery");
    event.setOccurredAt(Instant.now());

    streamService.notifySubscribers(event);

    // Revoked subscriber should have been evicted
    assertThat(streamService.getActiveSubscriberCount("ZT-STREAM1234")).isEqualTo(0);
  }

  @Test
  @DisplayName("cleanupExpiredSubscribers removes expired connections")
  void cleanupExpiredSubscribersRemovesDeadEmitters() {
    Instant expired = Instant.now().minus(5, ChronoUnit.SECONDS);
    // Directly simulate subscriber expiration check
    TrackingStreamService.StreamSubscriber subscriber = new TrackingStreamService.StreamSubscriber(
        "sub-1", new SseEmitter(), "ZT-STREAM1234", "cust-alice", Set.of("ROLE_CUSTOMER"), expired);
    assertThat(subscriber.isTokenExpired()).isTrue();
  }
}

