package com.zentro.parcelpricing.receipt;

import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller providing authorized access to official delivery receipts and printable PDF downloads.
 * Scoped strictly to booking owners (CUSTOMER / MERCHANT) and privileged operators (DISPATCHER / SERVICE).
 */
@RestController
@RequestMapping("/api/v1/parcels")
public class ReceiptController {

  private final ReceiptGenerationService receiptService;

  public ReceiptController(ReceiptGenerationService receiptService) {
    this.receiptService = receiptService;
  }

  @GetMapping(value = "/{id}/receipt", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT', 'DISPATCHER', 'SERVICE')")
  public ResponseEntity<ReceiptResponse> getReceipt(
      @PathVariable("id") UUID id,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication) {

    Receipt receipt = resolveAuthorizedReceipt(id, jwt, authentication);
    return ResponseEntity.ok(receiptService.toResponse(receipt));
  }

  @GetMapping(value = "/{id}/receipt/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT', 'DISPATCHER', 'SERVICE')")
  public ResponseEntity<byte[]> getReceiptPdf(
      @PathVariable("id") UUID id,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication) {

    Receipt receipt = resolveAuthorizedReceipt(id, jwt, authentication);
    byte[] pdfBytes = receiptService.generatePdfBytes(receipt);

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"receipt-" + receipt.trackingRef() + ".pdf\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdfBytes);
  }

  private Receipt resolveAuthorizedReceipt(UUID id, Jwt jwt, Authentication authentication) {
    String subject = jwt != null ? jwt.getSubject() : null;
    if (subject == null || subject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The access token has no subject.");
    }

    Optional<Receipt> receiptOpt;
    if (isPrivileged(authentication)) {
      receiptOpt = receiptService.generateReceipt(id);
    } else if (hasRole(authentication, "CUSTOMER")) {
      receiptOpt = receiptService.generateReceiptForCustomer(id, subject);
    } else if (hasRole(authentication, "MERCHANT")) {
      receiptOpt = receiptService.generateReceiptForMerchant(id, subject);
    } else {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
    }

    return receiptOpt.orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found for parcel " + id));
  }

  private static boolean isPrivileged(Authentication authentication) {
    if (authentication == null) return false;
    for (GrantedAuthority authority : authentication.getAuthorities()) {
      String role = authority.getAuthority();
      if ("ROLE_DISPATCHER".equals(role) || "ROLE_SERVICE".equals(role)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasRole(Authentication authentication, String roleName) {
    if (authentication == null) return false;
    for (GrantedAuthority authority : authentication.getAuthorities()) {
      if (("ROLE_" + roleName).equals(authority.getAuthority()) || roleName.equals(authority.getAuthority())) {
        return true;
      }
    }
    return false;
  }
}
