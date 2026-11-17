package com.zentro.trackingreporting.graphql.service;

import com.zentro.trackingreporting.graphql.dto.CustomerDashboardSummary;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Aggregate authoritative booking data from S2; tracking events remain owned by S4. */
@Service
public class CustomerDashboardService {
  private final CustomerBookingClient bookings;
  public CustomerDashboardService(CustomerBookingClient bookings) { this.bookings=bookings; }
  public CustomerDashboardSummary summary(Jwt jwt) { return bookings.summary(jwt); }
  public TrackingParcel liveParcel(Jwt jwt) { return bookings.live(jwt); }
  public TrackingParcel parcel(Jwt jwt,String reference) { return bookings.reference(jwt,reference); }
}
