package com.zentro.identitypartner.courier;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/courier/profile", "/api/v1/users/courier-profile"})
@PreAuthorize("hasRole('COURIER')")
public class CourierSelfProfileController {
  private final CourierProfileService courierService;

  public CourierSelfProfileController(CourierProfileService courierService) {
    this.courierService = courierService;
  }

  @GetMapping
  public CourierProfile getMyProfile(@AuthenticationPrincipal Jwt jwt) {
    return courierService.getByJwt(jwt);
  }

  @PostMapping("/provision")
  public CourierProfile provisionProfile(@AuthenticationPrincipal Jwt jwt) {
    return courierService.provisionCourier(jwt);
  }
}
