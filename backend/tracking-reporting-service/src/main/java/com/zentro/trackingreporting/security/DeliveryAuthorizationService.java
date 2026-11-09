package com.zentro.trackingreporting.security;

import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * Enforces backend authorization and resource ownership for delivery tracking requests.
 *
 * <p>Access rules:
 * <ul>
 *   <li>DISPATCHER: operational platform-wide access to all deliveries.</li>
 *   <li>CUSTOMER: access strictly restricted to parcels booked by this customer (customerSubject).</li>
 *   <li>MERCHANT: access strictly restricted to parcels owned by this merchant (merchantSubject).</li>
 *   <li>COURIER: access strictly restricted to parcels currently assigned to this courier (courierSubject).</li>
 *   <li>SERVICE / Unauthenticated / Non-matching owners: access denied.</li>
 * </ul>
 */
@Service
public class DeliveryAuthorizationService {

  private static final Logger log = LoggerFactory.getLogger(DeliveryAuthorizationService.class);

  public static final String ROLE_DISPATCHER = "ROLE_DISPATCHER";
  public static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";
  public static final String ROLE_MERCHANT = "ROLE_MERCHANT";
  public static final String ROLE_COURIER = "ROLE_COURIER";

  /**
   * Evaluates if the authenticated principal is authorized to access the given parcel's tracking data.
   */
  public boolean isAuthorized(Authentication authentication, TrackingParcel parcel) {
    if (authentication == null || parcel == null || !authentication.isAuthenticated()) {
      return false;
    }

    String subject = extractSubject(authentication);
    if (subject == null || subject.isBlank()) {
      return false;
    }

    Set<String> roles = authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .collect(Collectors.toSet());

    return isAuthorized(subject, roles, parcel);
  }

  /**
   * Overload accepting subject and roles, suitable for long-lived stream revalidation.
   */
  public boolean isAuthorized(String subject, Collection<String> roles, TrackingParcel parcel) {
    if (subject == null || subject.isBlank() || roles == null || parcel == null) {
      return false;
    }

    // DISPATCHER has operational tracking access across all parcels
    if (roles.contains(ROLE_DISPATCHER)) {
      return true;
    }

    // CUSTOMER can only access parcels where customerSubject matches
    if (roles.contains(ROLE_CUSTOMER) && Objects.equals(subject, parcel.getCustomerSubject())) {
      return true;
    }

    // MERCHANT can only access parcels where merchantSubject matches
    if (roles.contains(ROLE_MERCHANT) && parcel.getMerchantSubject() != null
        && Objects.equals(subject, parcel.getMerchantSubject())) {
      return true;
    }

    // COURIER can only access parcels assigned to them
    if (roles.contains(ROLE_COURIER) && parcel.getCourierSubject() != null
        && Objects.equals(subject, parcel.getCourierSubject())) {
      return true;
    }

    log.debug("Unauthorized tracking access attempt by subject {} with roles {} for parcel {}",
        subject, roles, parcel.getTrackingRef());
    return false;
  }

  /**
   * Safely extracts the token subject from authentication.
   */
  public String extractSubject(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken jwtAuth) {
      return jwtAuth.getToken().getSubject();
    }
    if (authentication.getPrincipal() instanceof Jwt jwt) {
      return jwt.getSubject();
    }
    return authentication.getName();
  }
}

