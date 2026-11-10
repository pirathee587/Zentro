package com.zentro.dispatchcourier.dispatch.controller;

import com.zentro.dispatchcourier.courier.service.IdentityCourierClient;
import com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository;
import com.zentro.dispatchcourier.dispatch.service.DispatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dispatch")
@PreAuthorize("hasRole('DISPATCHER')")
public class DispatchQueueController {
  private final DispatchService service;
  public DispatchQueueController(DispatchService service) { this.service = service; }

  @GetMapping({"/queue", "/unassigned"})
  @PreAuthorize("hasRole('DISPATCHER') and @dispatchPermissions.has(authentication, 'DISPATCH_VIEW')")
  public DispatchService.Queue queue(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "search", defaultValue = "") String search,
      @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(name = "sort", defaultValue = "newest") String sort) {
    return service.queue(jwt.getTokenValue(), search, from, to, sort);
  }

  @GetMapping("/eligible-couriers")
  @PreAuthorize("hasRole('DISPATCHER') and @dispatchPermissions.has(authentication, 'DISPATCH_VIEW')")
  public List<IdentityCourierClient.Courier> couriers(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "parcelId") UUID parcelId) {
    return service.couriers(parcelId, jwt.getTokenValue());
  }

  @GetMapping("/workload")
  @PreAuthorize("hasRole('DISPATCHER') and @dispatchPermissions.has(authentication, 'DISPATCH_VIEW')")
  public DispatchService.Workload workload(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "search", defaultValue = "") String search,
      @RequestParam(name = "availability", defaultValue = "") String availability) {
    return service.workload(jwt.getTokenValue(), search, availability);
  }

  public record AssignRequest(@NotNull UUID parcelId, @NotNull UUID courierId) {}

  @PostMapping("/assignments")
  @PreAuthorize("hasRole('DISPATCHER') and @dispatchPermissions.has(authentication, 'DISPATCH_ASSIGN')")
  public ResponseEntity<AssignmentRepository.Assignment> assign(@AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody AssignRequest request) {
    var result = service.assign(request.parcelId(), request.courierId(), jwt.getSubject(), jwt.getTokenValue());
    return ResponseEntity.status(201).body(result);
  }

  public record ReassignRequest(@NotNull UUID courierId) {}

  @PostMapping("/assignments/{parcelId}/reassign")
  public AssignmentRepository.Assignment reassign(@AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID parcelId, @Valid @RequestBody ReassignRequest request) {
    return service.reassign(parcelId, request.courierId(), jwt.getSubject(), jwt.getTokenValue());
  }

  @DeleteMapping("/assignments/{parcelId}")
  public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID parcelId) {
    service.cancel(parcelId, jwt.getSubject(), jwt.getTokenValue());
    return ResponseEntity.noContent().build();
  }
}
