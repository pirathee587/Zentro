package com.zentro.parcelpricing.controller;

import com.zentro.parcelpricing.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.dto.ParcelBookingResponse;
import com.zentro.parcelpricing.service.ParcelBookingService;
import com.zentro.parcelpricing.service.ParcelBookingService.BookingResult;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Customer delivery booking. The booking owner is always the token subject, never a request field. */
@RestController
@RequestMapping("/api/v1/parcels")
@PreAuthorize("hasRole('CUSTOMER')")
public class ParcelBookingController {
  static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
  static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

  private final ParcelBookingService bookingService;

  public ParcelBookingController(ParcelBookingService bookingService) {
    this.bookingService = bookingService;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
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
}
