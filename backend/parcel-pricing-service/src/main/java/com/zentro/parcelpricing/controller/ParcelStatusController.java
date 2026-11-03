package com.zentro.parcelpricing.controller;

import com.zentro.parcelpricing.dto.ParcelStatusResponse;
import com.zentro.parcelpricing.dto.UpdateParcelStatusRequest;
import com.zentro.parcelpricing.service.ParcelStatusService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Parcel status updates. Dispatchers call it through the gateway; internal services (for example S3 after
 * checking the courier's assignment) call it with a SERVICE token. The actor is always the token subject.
 */
@RestController
@RequestMapping("/api/v1/parcels")
@PreAuthorize("hasAnyRole('DISPATCHER', 'SERVICE')")
public class ParcelStatusController {
  private static final String DISPATCHER = "DISPATCHER";
  private static final String SERVICE = "SERVICE";

  private final ParcelStatusService statusService;

  public ParcelStatusController(ParcelStatusService statusService) {
    this.statusService = statusService;
  }

  @PatchMapping(path = "/{parcelId}/status",
      consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ParcelStatusResponse updateStatus(
      @PathVariable UUID parcelId,
      @Valid @RequestBody UpdateParcelStatusRequest request,
      Authentication authentication) {
    String actorSubject = authentication != null && authentication.getPrincipal() instanceof Jwt jwt
        ? jwt.getSubject() : null;
    if (actorSubject == null || actorSubject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The access token has no subject.");
    }
    return statusService.updateStatus(parcelId, request.status(), request.reason(), request.note(),
        actorSubject, actorRole(authentication));
  }

  /** The role the change is recorded with. A human dispatcher wins over a service role on the same token. */
  private static String actorRole(Authentication authentication) {
    boolean dispatcher = false;
    for (GrantedAuthority authority : authentication.getAuthorities()) {
      if (("ROLE_" + DISPATCHER).equals(authority.getAuthority())) {
        dispatcher = true;
      }
    }
    return dispatcher ? DISPATCHER : SERVICE;
  }
}
