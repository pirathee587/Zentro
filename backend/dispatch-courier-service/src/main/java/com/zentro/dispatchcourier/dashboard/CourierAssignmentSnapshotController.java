package com.zentro.dispatchcourier.dashboard;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CourierAssignmentSnapshotController {
  private final CourierIdentityReadClient identity;
  private final CourierAssignmentSnapshotRepository assignments;
  public CourierAssignmentSnapshotController(CourierIdentityReadClient identity,
      CourierAssignmentSnapshotRepository assignments) {
    this.identity = identity; this.assignments = assignments;
  }

  @GetMapping("/internal/courier-dashboard/assignments")
  @PreAuthorize("hasRole('COURIER') and !hasRole('SERVICE')")
  public ResponseEntity<List<CourierAssignmentSnapshotRepository.Assignment>> snapshot(
      @AuthenticationPrincipal Jwt courier) {
    String courierToken = courier.getTokenValue();
    if (courier.getSubject() == null || courier.getSubject().isBlank() || courier.getSubject().length() > 255)
      throw unauthorized();
    var resolved = identity.resolve(courierToken);
    return ResponseEntity.ok().header("Cache-Control", "no-store")
        .body(assignments.snapshot(resolved.userId(), resolved.profileId()));
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid courier credentials are required.");
  }
}
