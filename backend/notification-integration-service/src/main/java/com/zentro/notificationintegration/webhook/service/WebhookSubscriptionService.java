package com.zentro.notificationintegration.webhook.service;

import com.zentro.notificationintegration.webhook.client.IdentityMerchantClient;
import com.zentro.notificationintegration.webhook.dto.response.WebhookDeliveryPage;
import com.zentro.notificationintegration.webhook.dto.response.WebhookDeliveryResponse;
import com.zentro.notificationintegration.webhook.entity.WebhookSubscription;
import com.zentro.notificationintegration.webhook.exception.WebhookNotFoundException;
import com.zentro.notificationintegration.webhook.exception.WebhooksUnavailableException;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import com.zentro.notificationintegration.webhook.repository.WebhookSubscriptionRepository;
import com.zentro.notificationintegration.webhook.security.WebhookSecretCipher;
import com.zentro.notificationintegration.webhook.security.WebhookSecretGenerator;
import com.zentro.notificationintegration.webhook.security.WebhookSecretGenerator.GeneratedSecret;
import com.zentro.notificationintegration.webhook.validation.WebhookUrlValidator;
import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Merchant webhook configuration rules. The merchant is always the token subject, so a merchant can only see and
 * change its own subscription and deliveries. Registering and rotating also require an approved, active merchant
 * (checked with S1), generate a new secret and return it once. Logs never contain the secret or the token.
 */
@Service
public class WebhookSubscriptionService {
  static final int MAX_PAGE_SIZE = 100;

  private static final Logger log = LoggerFactory.getLogger(WebhookSubscriptionService.class);

  /** Result of a registration: the stored subscription, the one-time secret and whether it is new. */
  public record Registration(WebhookSubscription subscription, String secret, boolean created) {
    @Override
    public String toString() {
      return "Registration[id=" + subscription.id() + ", created=" + created + "]";
    }
  }

  private final WebhookSubscriptionRepository subscriptions;
  private final WebhookDeliveryRepository deliveries;
  private final WebhookUrlValidator validator;
  private final WebhookSecretGenerator generator;
  private final WebhookSecretCipher cipher;
  private final IdentityMerchantClient identity;

  public WebhookSubscriptionService(WebhookSubscriptionRepository subscriptions,
      WebhookDeliveryRepository deliveries, WebhookUrlValidator validator, WebhookSecretGenerator generator,
      WebhookSecretCipher cipher, IdentityMerchantClient identity) {
    this.subscriptions = subscriptions;
    this.deliveries = deliveries;
    this.validator = validator;
    this.generator = generator;
    this.cipher = cipher;
    this.identity = identity;
  }

  /** Registers the merchant's endpoint, or replaces its URL and secret and enables it again. */
  public Registration register(String merchantId, String bearerToken, String url) {
    identity.requireApprovedMerchant(bearerToken);
    URI endpoint = validator.validate(url);
    requireCipher();

    boolean existed = subscriptions.findByMerchant(merchantId).isPresent();
    GeneratedSecret secret = generator.generate();
    WebhookSubscription saved = subscriptions.upsert(merchantId, endpoint.toString(),
        cipher.encrypt(secret.value(), merchantId), secret.hint());
    log.info("Webhook endpoint {}: merchantId={} host={}", existed ? "replaced" : "registered", merchantId,
        endpoint.getHost());
    return new Registration(saved, secret.value(), !existed);
  }

  public Optional<WebhookSubscription> find(String merchantId) {
    return subscriptions.findByMerchant(merchantId);
  }

  /** Disables the merchant's endpoint; no further events are sent to it. */
  public void disable(String merchantId) {
    if (!subscriptions.deactivate(merchantId)) {
      throw new WebhookNotFoundException("No webhook endpoint is registered.");
    }
    log.info("Webhook endpoint disabled: merchantId={}", merchantId);
  }

  /** Replaces the signing secret of the merchant's active endpoint and returns the new secret once. */
  public Registration rotateSecret(String merchantId, String bearerToken) {
    identity.requireApprovedMerchant(bearerToken);
    requireCipher();
    GeneratedSecret secret = generator.generate();
    WebhookSubscription saved = subscriptions
        .updateSecret(merchantId, cipher.encrypt(secret.value(), merchantId), secret.hint())
        .orElseThrow(() -> new WebhookNotFoundException("No active webhook endpoint is registered."));
    log.info("Webhook secret rotated: merchantId={}", merchantId);
    return new Registration(saved, secret.value(), false);
  }

  /** One page of the merchant's own deliveries; {@code size} is limited to 1..{@value #MAX_PAGE_SIZE}. */
  public WebhookDeliveryPage deliveries(String merchantId, int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var items = deliveries.listByMerchant(merchantId, safeSize, (long) safePage * safeSize).stream()
        .map(WebhookDeliveryResponse::from)
        .toList();
    return new WebhookDeliveryPage(items, safePage, safeSize, deliveries.countByMerchant(merchantId));
  }

  private void requireCipher() {
    if (!cipher.isConfigured()) {
      throw new WebhooksUnavailableException("Webhooks are not configured on this server.");
    }
  }
}
