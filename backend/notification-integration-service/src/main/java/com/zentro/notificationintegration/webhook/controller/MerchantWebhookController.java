package com.zentro.notificationintegration.webhook.controller;

import com.zentro.notificationintegration.webhook.dto.request.RegisterWebhookRequest;
import com.zentro.notificationintegration.webhook.dto.response.WebhookDeliveryPage;
import com.zentro.notificationintegration.webhook.dto.response.WebhookSecretResponse;
import com.zentro.notificationintegration.webhook.dto.response.WebhookSubscriptionResponse;
import com.zentro.notificationintegration.webhook.exception.MerchantVerificationException;
import com.zentro.notificationintegration.webhook.exception.WebhookNotFoundException;
import com.zentro.notificationintegration.webhook.service.WebhookSubscriptionService;
import com.zentro.notificationintegration.webhook.service.WebhookSubscriptionService.Registration;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Merchant webhook configuration (role MERCHANT). The merchant is the token subject; there is no merchant id in
 * the path, so a merchant can only reach its own data. Responses that contain a new secret are not cacheable.
 */
@RestController
@RequestMapping("/api/v1/merchant/webhooks")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantWebhookController {
  static final String PATH = "/api/v1/merchant/webhooks";

  private final WebhookSubscriptionService service;

  public MerchantWebhookController(WebhookSubscriptionService service) {
    this.service = service;
  }

  /** Registers or replaces the endpoint: 201 when new, 200 when replaced. The secret is in this response only. */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WebhookSecretResponse> register(@AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody RegisterWebhookRequest request) {
    Registration result = service.register(merchantId(jwt), jwt.getTokenValue(), request.url());
    WebhookSecretResponse body = WebhookSecretResponse.from(result.subscription(), result.secret());
    ResponseEntity.BodyBuilder response = result.created()
        ? ResponseEntity.created(URI.create(PATH))
        : ResponseEntity.ok();
    return response.cacheControl(CacheControl.noStore()).body(body);
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public WebhookSubscriptionResponse get(@AuthenticationPrincipal Jwt jwt) {
    return service.find(merchantId(jwt))
        .map(WebhookSubscriptionResponse::from)
        .orElseThrow(() -> new WebhookNotFoundException("No webhook endpoint is registered."));
  }

  @DeleteMapping
  public ResponseEntity<Void> disable(@AuthenticationPrincipal Jwt jwt) {
    service.disable(merchantId(jwt));
    return ResponseEntity.noContent().build();
  }

  /** Rotates the signing secret of the active endpoint; the new secret is in this response only. */
  @PostMapping(value = "/secret", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WebhookSecretResponse> rotateSecret(@AuthenticationPrincipal Jwt jwt) {
    Registration result = service.rotateSecret(merchantId(jwt), jwt.getTokenValue());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(WebhookSecretResponse.from(result.subscription(), result.secret()));
  }

  @GetMapping(value = "/deliveries", produces = MediaType.APPLICATION_JSON_VALUE)
  public WebhookDeliveryPage deliveries(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    return service.deliveries(merchantId(jwt), page, size);
  }

  private static String merchantId(Jwt jwt) {
    String subject = jwt == null ? null : jwt.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new MerchantVerificationException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
          "The access token has no subject.");
    }
    return subject;
  }
}
