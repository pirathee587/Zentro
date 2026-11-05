package com.zentro.trackingreporting.tracking.controller;

import com.zentro.trackingreporting.tracking.dto.CustomerDeliveryListResponse;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tracking")
@PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT')")
public class CustomerTrackingController {

  private final TrackingService trackingService;

  public CustomerTrackingController(TrackingService trackingService) {
    this.trackingService = trackingService;
  }

  /**
   * Authenticated customer delivery history.
   * Access is strictly isolated to the authenticated token subject.
   */
  @GetMapping(path = "/mine", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<CustomerDeliveryListResponse> getMyDeliveries(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "page", defaultValue = "1") int page,
      @RequestParam(name = "size", required = false) Integer size,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "search", required = false) String search) {

    String customerSubject = jwt != null ? jwt.getSubject() : null;
    if (customerSubject == null || customerSubject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The access token has no subject.");
    }

    int pageSize = limit != null ? limit : (size != null ? size : 10);
    String searchQuery = query != null ? query : search;

    CustomerDeliveryListResponse response = trackingService.getCustomerDeliveries(
        customerSubject,
        page,
        pageSize,
        status,
        searchQuery
    );

    return ResponseEntity.ok(response);
  }
}

