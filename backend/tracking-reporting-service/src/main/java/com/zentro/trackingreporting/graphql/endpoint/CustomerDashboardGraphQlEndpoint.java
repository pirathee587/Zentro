package com.zentro.trackingreporting.graphql.endpoint;

import com.zentro.trackingreporting.graphql.dto.CustomerDashboardSummary;
import com.zentro.trackingreporting.graphql.dto.DashboardFeeEstimate;
import com.zentro.trackingreporting.graphql.service.DashboardPricingClient;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.security.oauth2.jwt.Jwt;
import com.zentro.trackingreporting.graphql.dto.DeliveryDetailsDto;
import com.zentro.trackingreporting.graphql.service.CustomerDashboardService;
import com.zentro.trackingreporting.security.DeliveryAuthorizationService;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Controller
public class CustomerDashboardGraphQlEndpoint {
  private final DashboardPricingClient pricing;
  private final CustomerDashboardService dashboard;
  private final DeliveryAuthorizationService authorization;
  private final com.zentro.trackingreporting.graphql.service.CustomerBookingClient bookings;
  @org.springframework.beans.factory.annotation.Autowired
  public CustomerDashboardGraphQlEndpoint(CustomerDashboardService dashboard, DeliveryAuthorizationService authorization,
      DashboardPricingClient pricing,
      @org.springframework.lang.Nullable com.zentro.trackingreporting.graphql.service.CustomerBookingClient bookings) {
    this.pricing = pricing;
    this.dashboard = dashboard;
    this.authorization = authorization;
    this.bookings = bookings;
  }
  public CustomerDashboardGraphQlEndpoint(CustomerDashboardService dashboard, DeliveryAuthorizationService authorization,
      DashboardPricingClient pricing) {
    this(dashboard, authorization, pricing, null);
  }
  @QueryMapping public CustomerDashboardSummary customerDashboardSummary(Authentication authentication) {
    return dashboard.summary(customer(authentication));
  }
  @QueryMapping public DeliveryDetailsDto customerDelivery(@Argument("trackingRef") String trackingRef, Authentication authentication) {
    if (trackingRef==null || !trackingRef.matches("[A-Za-z0-9_-]{6,32}"))
      throw new IllegalArgumentException("Invalid tracking reference");
    Jwt jwt = customer(authentication);
    var parcel = dashboard.parcel(jwt, trackingRef);
    if (parcel == null) return null;
    return DeliveryDetailsDto.from(parcel).withCourier(bookings == null ? null : bookings.assignedCourier(jwt, parcel.getId()))
        .withEvents(bookings == null ? null : bookings.customerHistory(jwt, parcel.getTrackingRef()));
  }
  @QueryMapping public DeliveryDetailsDto customerLiveDelivery(Authentication authentication) {
    Jwt jwt = customer(authentication);
    var parcel = dashboard.liveParcel(jwt);
    if (parcel == null) return null;
    return DeliveryDetailsDto.from(parcel).withCourier(bookings == null ? null : bookings.assignedCourier(jwt, parcel.getId()))
        .withEvents(bookings == null ? null : bookings.customerHistory(jwt, parcel.getTrackingRef()));
  }
  @QueryMapping public DashboardFeeEstimate dashboardFeeEstimate(
      @Argument("pickupCity") String pickupCity,
      @Argument("deliveryCity") String deliveryCity,
      @Argument("weightKg") double weightKg,
      @Argument("destinationType") String destinationType,
      @Argument("parcelCategory") String parcelCategory,
      Authentication authentication) {
    customer(authentication);
    if (!(authentication.getPrincipal() instanceof Jwt jwt))
      throw new AccessDeniedException("Customer authentication required");
    return pricing.estimate(jwt, pickupCity, deliveryCity, weightKg, destinationType, parcelCategory);
  }
  private Jwt customer(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated() || authentication.getAuthorities().stream()
        .noneMatch(role -> role.getAuthority().equals("ROLE_CUSTOMER"))) {
      throw new AccessDeniedException("Customer authentication required");
    }
    String subject = authorization.extractSubject(authentication);
    if (subject == null || subject.isBlank()) throw new AccessDeniedException("Customer authentication required");
    if (!(authentication.getPrincipal() instanceof Jwt jwt)) throw new AccessDeniedException("Customer authentication required");
    return jwt;
  }
}
