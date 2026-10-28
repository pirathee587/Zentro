package com.zentro.parcelpricing.controller;

import com.zentro.parcelpricing.domain.DeliveryZone;
import com.zentro.parcelpricing.service.ZoneService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/zones")
@PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER', 'ADMIN')")
public class ZoneController {
  private final ZoneService zoneService;

  public ZoneController(ZoneService zoneService) {
    this.zoneService = zoneService;
  }

  @GetMapping
  public List<DeliveryZone> getZones() {
    return zoneService.getActiveZones();
  }
}
