package com.zentro.parcelpricing.controller;

import com.zentro.parcelpricing.dto.CreateMerchantOrderRequest;
import com.zentro.parcelpricing.dto.MerchantOrderResponse;
import com.zentro.parcelpricing.service.IdentityMerchantClient;
import com.zentro.parcelpricing.service.MerchantOrderService;
import com.zentro.parcelpricing.service.MerchantOrderService.OrderResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Merchant-facing API for authorized merchant operations.
 * Requires ROLE_MERCHANT, validates merchant approval and active status with Identity,
 * safely handles duplicate external references, and strictly scopes operations to the authenticated merchant.
 */
@RestController
@RequestMapping("/api/v1/merchant/orders")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantOrderController {
  private final MerchantOrderService orderService;
  private final IdentityMerchantClient identityMerchantClient;

  public MerchantOrderController(
      MerchantOrderService orderService,
      IdentityMerchantClient identityMerchantClient) {
    this.orderService = orderService;
    this.identityMerchantClient = identityMerchantClient;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<MerchantOrderResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody CreateMerchantOrderRequest request) {
    String merchantId = getMerchantSubject(jwt);
    identityMerchantClient.requireApprovedMerchant(jwt.getTokenValue());

    OrderResult result = orderService.createOrder(merchantId, request);
    MerchantOrderResponse body = result.order();

    if (!result.created()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.created(URI.create("/api/v1/merchant/orders/" + body.id())).body(body);
  }

  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<MerchantOrderResponse> getById(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("id") UUID id) {
    String merchantId = getMerchantSubject(jwt);
    identityMerchantClient.requireApprovedMerchant(jwt.getTokenValue());

    return orderService.findById(id, merchantId)
        .map(ResponseEntity::ok)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found."));
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public List<MerchantOrderResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    String merchantId = getMerchantSubject(jwt);
    identityMerchantClient.requireApprovedMerchant(jwt.getTokenValue());

    int clampedLimit = Math.min(Math.max(limit, 1), 100);
    return orderService.listByMerchant(merchantId, clampedLimit);
  }

  private static String getMerchantSubject(Jwt jwt) {
    String subject = jwt == null ? null : jwt.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The access token has no subject.");
    }
    return subject;
  }
}
