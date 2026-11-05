package com.zentro.dispatchcourier.courier.controller;

import com.zentro.dispatchcourier.courier.dto.ProofResponse;
import com.zentro.dispatchcourier.courier.dto.SubmitProofRequest;
import com.zentro.dispatchcourier.courier.repository.ProofRepository;
import com.zentro.dispatchcourier.courier.service.ProofOfDeliveryService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/courier/deliveries")
@PreAuthorize("hasRole('COURIER')")
public class CourierDeliveryController {
  private final ProofOfDeliveryService service;
  public CourierDeliveryController(ProofOfDeliveryService service) { this.service = service; }
  @GetMapping
  public ResponseEntity<List<ProofRepository.DeliverySummary>> list(@AuthenticationPrincipal Jwt jwt) {
    return privateResponse(service.deliveries(jwt));
  }
  @GetMapping("/{parcelId}")
  public ResponseEntity<ProofRepository.DeliverySummary> delivery(@PathVariable("parcelId") UUID parcelId,
      @AuthenticationPrincipal Jwt jwt) { return privateResponse(service.delivery(parcelId, jwt)); }
  @GetMapping("/{parcelId}/proof")
  public ResponseEntity<ProofResponse> proof(@PathVariable("parcelId") UUID parcelId,
      @AuthenticationPrincipal Jwt jwt) { return privateResponse(service.proof(parcelId, jwt)); }
  @PostMapping("/{parcelId}/proof")
  public ResponseEntity<ProofResponse> submit(@PathVariable("parcelId") UUID parcelId,
      @AuthenticationPrincipal Jwt jwt, @RequestBody SubmitProofRequest request) {
    var result = service.submit(parcelId, jwt, request);
    return ResponseEntity.status(result.created() ? 201 : 200).header("Cache-Control", "no-store")
        .body(ProofResponse.from(result.proof()));
  }
  private static <T> ResponseEntity<T> privateResponse(T body) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
  }
}
