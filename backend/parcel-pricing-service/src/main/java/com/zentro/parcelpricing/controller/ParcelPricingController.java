package com.zentro.parcelpricing.controller;

import com.zentro.parcelpricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.service.PricingService;
import com.zentro.parcelpricing.service.ZoneService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/parcels")
@PreAuthorize("hasAnyRole('CUSTOMER', 'DISPATCHER')")
public class ParcelPricingController {
  private final PricingService pricingService;
  private final ZoneService zoneService;

  public ParcelPricingController(PricingService pricingService, ZoneService zoneService) {
    this.pricingService = pricingService;
    this.zoneService = zoneService;
  }

  @PostMapping(path = {"/estimate", "/estimate-fee"})
  public FeeEstimateResponse estimate(@Valid @RequestBody FeeEstimateRequest request) {
    if (request.originZone() != null && !zoneService.isValidZone(request.originZone())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported origin zone: " + request.originZone());
    }
    if (request.destinationZone() != null && !zoneService.isValidZone(request.destinationZone())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported destination zone: " + request.destinationZone());
    }
    try {
      return pricingService.estimateFee(request);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }
}
