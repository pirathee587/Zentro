package com.zentro.identitypartner.courier;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/couriers")
@PreAuthorize("hasRole('DISPATCHER')")
public class AdminCourierController {
  private final CourierProfileService courierService;

  public AdminCourierController(CourierProfileService courierService) {
    this.courierService = courierService;
  }

  @GetMapping
  public List<CourierProfile> listCouriers(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "active", required = false) Boolean active,
      @RequestParam(name = "zone", required = false) String zone,
      @RequestParam(name = "search", required = false) String search) {
    return courierService.listCouriers(status, active, zone, search);
  }

  @GetMapping("/{courierId}")
  public CourierProfile getCourier(@PathVariable("courierId") UUID courierId) {
    return courierService.getByCourierId(courierId);
  }

  @PatchMapping("/{courierId}")
  public CourierProfile updateCourier(
      @PathVariable("courierId") UUID courierId,
      @Valid @RequestBody UpdateCourierProfileRequest request) {
    return courierService.updateCourierProfile(courierId, request);
  }
}
