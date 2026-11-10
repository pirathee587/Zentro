package com.zentro.parcelpricing.pricing.controller;

import com.zentro.parcelpricing.pricing.rules.DeliveryZone;
import com.zentro.parcelpricing.pricing.service.ZoneService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/zones")
@PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER')")
public class ZoneController {
  private final ZoneService zoneService;

  public ZoneController(ZoneService zoneService) {
    this.zoneService = zoneService;
  }

  @GetMapping
  public List<DeliveryZone> getZones() {
    return zoneService.getActiveZones();
  }

  @GetMapping("/{code}")
  public DeliveryZone getZoneByCode(@PathVariable("code") String code) {
    return zoneService.getZoneByCode(code)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery zone not found: " + code));
  }
}
