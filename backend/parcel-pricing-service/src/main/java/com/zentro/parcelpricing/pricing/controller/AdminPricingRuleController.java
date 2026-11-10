package com.zentro.parcelpricing.pricing.controller;

import com.zentro.parcelpricing.pricing.rules.PricingRule;
import com.zentro.parcelpricing.pricing.dto.PricingRuleUpdateRequest;
import com.zentro.parcelpricing.pricing.service.PricingService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    return pricingService.updateStandardRule(request);
  }
}
