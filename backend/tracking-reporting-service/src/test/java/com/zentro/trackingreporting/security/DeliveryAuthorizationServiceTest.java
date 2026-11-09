package com.zentro.trackingreporting.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class DeliveryAuthorizationServiceTest {

  private DeliveryAuthorizationService service;
  private TrackingParcel parcel;

  @BeforeEach
  void setUp() {
    service = new DeliveryAuthorizationService();
    parcel = new TrackingParcel();
    parcel.setTrackingRef("ZT-TEST123456");
    parcel.setCustomerSubject("customer-alice");
    parcel.setMerchantSubject("merchant-acme");
    parcel.setCourierSubject("courier-bob");
  }

  @Test
  @DisplayName("Customer can access their own parcel")
  void customerOwnParcelAllowed() {
    TestingAuthenticationToken auth = new TestingAuthenticationToken(
        "customer-alice", "credentials",
        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

    assertThat(service.isAuthorized(auth, parcel)).isTrue();
  }

  @Test
  @DisplayName("Customer cannot access another customer's parcel")
  void customerOtherParcelDenied() {
    TestingAuthenticationToken auth = new TestingAuthenticationToken(
        "customer-eve", "credentials",
        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

    assertThat(service.isAuthorized(auth, parcel)).isFalse();
  }

  @Test
  @DisplayName("Merchant can access their own parcel")
  void merchantOwnParcelAllowed() {
    TestingAuthenticationToken auth = new TestingAuthenticationToken(
        "merchant-acme", "credentials",
        List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));

    assertThat(service.isAuthorized(auth, parcel)).isTrue();
  }

  @Test
  @DisplayName("Merchant cannot access unrelated parcel")
  void merchantOtherParcelDenied() {
    TestingAuthenticationToken auth = new TestingAuthenticationToken(
        "merchant-other", "credentials",
        List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));

    assertThat(service.isAuthorized(auth, parcel)).isFalse();
  }

  @Test
  @DisplayName("Assigned courier can access assigned parcel")
  void assignedCourierAllowed() {
    TestingAuthenticationToken auth = new TestingAuthenticationToken(
        "courier-bob", "credentials",
        List.of(new SimpleGrantedAuthority("ROLE_COURIER")));

    assertThat(service.isAuthorized(auth, parcel)).isTrue();
  }

  @Test
  @DisplayName("Unassigned courier cannot access parcel")
  void unassignedCourierDenied() {
    TestingAuthenticationToken auth = new TestingAuthenticationToken(
        "courier-mallory", "credentials",
        List.of(new SimpleGrantedAuthority("ROLE_COURIER")));

    assertThat(service.isAuthorized(auth, parcel)).isFalse();
  }

  @Test
  @DisplayName("Dispatcher has platform-wide operational access to any parcel")
  void dispatcherAllowed() {
    TestingAuthenticationToken auth = new TestingAuthenticationToken(
        "dispatcher-dave", "credentials",
        List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER")));

    assertThat(service.isAuthorized(auth, parcel)).isTrue();
  }

  @Test
  @DisplayName("Service token alone is denied from tracking streams")
  void serviceOnlyDenied() {
    TestingAuthenticationToken auth = new TestingAuthenticationToken(
        "service-worker", "credentials",
        List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));

    assertThat(service.isAuthorized(auth, parcel)).isFalse();
  }

  @Test
  @DisplayName("Unauthenticated or null authentication is denied")
  void unauthenticatedDenied() {
    assertThat(service.isAuthorized(null, parcel)).isFalse();

    TestingAuthenticationToken unauth = new TestingAuthenticationToken("user", "pass");
    unauth.setAuthenticated(false);
    assertThat(service.isAuthorized(unauth, parcel)).isFalse();
  }

  @Test
  @DisplayName("Subject and roles overload works correctly for stream revalidation")
  void subjectAndRolesOverload() {
    assertThat(service.isAuthorized("customer-alice", Set.of("ROLE_CUSTOMER"), parcel)).isTrue();
    assertThat(service.isAuthorized("customer-eve", Set.of("ROLE_CUSTOMER"), parcel)).isFalse();
    assertThat(service.isAuthorized("courier-bob", Set.of("ROLE_COURIER"), parcel)).isTrue();
    assertThat(service.isAuthorized("courier-mallory", Set.of("ROLE_COURIER"), parcel)).isFalse();
    assertThat(service.isAuthorized("anyone", Set.of("ROLE_DISPATCHER"), parcel)).isTrue();
    assertThat(service.isAuthorized("anyone", Set.of("ROLE_SERVICE"), parcel)).isFalse();
  }
}

