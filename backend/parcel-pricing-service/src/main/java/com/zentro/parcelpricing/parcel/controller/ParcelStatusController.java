package com.zentro.parcelpricing.parcel.controller;

import com.zentro.parcelpricing.parcel.dto.ParcelStatusResponse;
import com.zentro.parcelpricing.parcel.dto.UpdateParcelStatusRequest;
import com.zentro.parcelpricing.parcel.service.ParcelStatusService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Human dispatcher status updates. Internal service status changes must use the authenticated gRPC contract.
 */
@RestController
@RequestMapping("/api/v1/parcels")
@PreAuthorize("hasRole('DISPATCHER')")
public class ParcelStatusController {
  private static final String DISPATCHER = "DISPATCHER";

  private final ParcelStatusService statusService;

  public ParcelStatusController(ParcelStatusService statusService) {
    this.statusService = statusService;
  }

  @PatchMapping(path = "/{parcelId}/status",
      consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ParcelStatusResponse updateStatus(
      @PathVariable("parcelId") UUID parcelId,
      @Valid @RequestBody UpdateParcelStatusRequest request,
      Authentication authentication) {
    String actorSubject = authentication != null && authentication.getPrincipal() instanceof Jwt jwt
        ? jwt.getSubject() : null;
    if (actorSubject == null || actorSubject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The access token has no subject.");
    }
    return statusService.updateStatus(parcelId, request.status(), request.reason(), request.note(),
        actorSubject, DISPATCHER);
  }
}
