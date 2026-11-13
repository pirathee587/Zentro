package com.zentro.parcelpricing.parcel.controller;

import com.zentro.parcelpricing.parcel.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.parcel.dto.ParcelBookingResponse;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService.BookingResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Customer delivery booking. The booking owner is always the token subject, never a request field. */
@RestController
@RequestMapping("/api/v1/parcels")
public class ParcelBookingController {
  static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
  static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

  private final ParcelBookingService bookingService;

  public ParcelBookingController(ParcelBookingService bookingService) {
    this.bookingService = bookingService;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('CUSTOMER')")
  public ResponseEntity<ParcelBookingResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
      @Valid @RequestBody CreateParcelBookingRequest request) {
    String customerSubject = jwt == null ? null : jwt.getSubject();
    if (customerSubject == null || customerSubject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The access token has no subject.");
    }
    if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Idempotency-Key must be between 1 and " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters.");
    }

    BookingResult result = bookingService.book(customerSubject, request, idempotencyKey);
    ParcelBookingResponse body = result.booking();
    if (!result.created()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.created(URI.create("/api/v1/parcels/" + body.id())).body(body);
  }

  /**
   * Retrieves a parcel booking by its UUID.
   *
   * <ul>
   *   <li>CUSTOMER: may only retrieve their own parcel (scoped by token subject). A foreign parcel
   *       or a non-existent ID returns 404 to prevent resource enumeration.</li>
   *   <li>DISPATCHER / SERVICE: may retrieve any parcel. No ownership scope is applied.
   *       These roles reach this endpoint only via trusted internal paths; the gateway does not
   *       forward GET /api/v1/parcels/{id} with dispatcher or service tokens. No gateway change
   *       is required for CUSTOMER access via POST /api/v1/parcels/**; a separate gateway route
   *       update is required before DISPATCHER/SERVICE-scoped retrieval is reachable externally
   *       (coordinate with Main 02 / gateway maintainer).</li>
   * </ul>
   */
  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasAnyRole('CUSTOMER', 'DISPATCHER', 'SERVICE')")
  public ResponseEntity<ParcelBookingResponse> getById(
      @PathVariable("id") UUID id,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication) {
    String subject = jwt == null ? null : jwt.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The access token has no subject.");
    }

    ParcelBookingResponse body;
    if (isPrivileged(authentication)) {
      // DISPATCHER or SERVICE: unrestricted read
      body = bookingService.getById(id);
    } else {
      // CUSTOMER: owner-scoped, returns 404 for foreign or missing parcel
      body = bookingService.getByIdAndOwner(id, subject);
    }
    return ResponseEntity.ok(body);
  }

  /** Returns true when the authenticated principal holds DISPATCHER or SERVICE authority. */
  private static boolean isPrivileged(Authentication authentication) {
    if (authentication == null) return false;
    for (GrantedAuthority authority : authentication.getAuthorities()) {
      String a = authority.getAuthority();
      if ("ROLE_DISPATCHER".equals(a) || "ROLE_SERVICE".equals(a)) {
        return true;
      }
    }
    return false;
  }
}
