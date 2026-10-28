package com.zentro.parcelpricing.controller;

import com.zentro.parcelpricing.domain.PricingRule;
import com.zentro.parcelpricing.dto.PricingRuleUpdateRequest;
import com.zentro.parcelpricing.service.PricingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/pricing-rules")
@PreAuthorize("hasRole('DISPATCHER')")
public class AdminPricingRuleController {
  private final PricingService pricingService;

  public AdminPricingRuleController(PricingService pricingService) {
    this.pricingService = pricingService;
  }

  @GetMapping
  public PricingRule getCurrentRule() {
    return pricingService.getActiveRule();
  }

  @PutMapping
  public PricingRule updateRule(@Valid @RequestBody PricingRuleUpdateRequest request) {
    try {
      return pricingService.updateStandardRule(request);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }
}
