package com.zentro.notificationintegration.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zentro.notificationintegration.webhook.client.IdentityMerchantClient;
import com.zentro.notificationintegration.webhook.config.WebhookProperties;
import com.zentro.notificationintegration.webhook.exception.MerchantVerificationException;
import com.zentro.notificationintegration.webhook.exception.WebhookNotFoundException;
import com.zentro.notificationintegration.webhook.exception.WebhooksUnavailableException;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import com.zentro.notificationintegration.webhook.repository.WebhookSubscriptionRepository;
import com.zentro.notificationintegration.webhook.security.WebhookSecretCipher;
import com.zentro.notificationintegration.webhook.security.WebhookSecretGenerator;
import com.zentro.notificationintegration.webhook.validation.WebhookUrlValidator;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WebhookSubscriptionServiceTest {
  private final WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
  private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
  private final IdentityMerchantClient identity = mock(IdentityMerchantClient.class);
  private final WebhookUrlValidator validator =
      new WebhookUrlValidator(false, host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});

  private WebhookSubscriptionService service(String key) {
    WebhookProperties properties = new WebhookProperties(false, false, key, "merchant-webhooks",
        new WebhookProperties.Retry(8, 5000, 10000, 1800000), new WebhookProperties.Http(2000, 5000),
        new WebhookProperties.Health(60, 10));
    return new WebhookSubscriptionService(subscriptions, deliveries, validator, new WebhookSecretGenerator(),
        new WebhookSecretCipher(properties), identity);
  }

  @Test
  @DisplayName("Without an encryption key, registering and rotating return 503 and store nothing")
  void noKeyIsUnavailable() {
    WebhookSubscriptionService service = service("");

    assertThatThrownBy(() -> service.register("m", "token", "https://hooks.example.com/h"))
        .isInstanceOf(WebhooksUnavailableException.class);
    assertThatThrownBy(() -> service.rotateSecret("m", "token")).isInstanceOf(WebhooksUnavailableException.class);
    verifyNoInteractions(subscriptions);
  }

  @Test
  @DisplayName("The merchant approval check runs before the URL is even looked at")
  void approvalFirst() {
    doThrow(new MerchantVerificationException(HttpStatus.FORBIDDEN, "MERCHANT_NOT_APPROVED", "no"))
        .when(identity).requireApprovedMerchant("token");

    assertThatThrownBy(() -> service("").register("m", "token", "http://10.0.0.1/"))
        .isInstanceOf(MerchantVerificationException.class);
    verifyNoInteractions(subscriptions);
  }

  @Test
  @DisplayName("Disabling a merchant that never registered is 404")
  void disableUnknown() {
    when(subscriptions.deactivate("m")).thenReturn(false);

    assertThatThrownBy(() -> service("").disable("m")).isInstanceOf(WebhookNotFoundException.class);
  }

  @Test
  @DisplayName("Paging is clamped: page >= 0 and 1 <= size <= 100")
  void pagingIsClamped() {
    when(deliveries.listByMerchant(eq("m"), anyInt(), anyLong())).thenReturn(List.of());

    assertThat(service("").deliveries("m", -3, 0)).satisfies(page -> {
      assertThat(page.page()).isZero();
      assertThat(page.size()).isEqualTo(1);
    });
    assertThat(service("").deliveries("m", 2, 500).size()).isEqualTo(100);
    verify(deliveries).listByMerchant("m", 100, 200L);
    verify(deliveries, org.mockito.Mockito.never()).listByMerchant(eq("other"), anyInt(), anyLong());
    verifyNoInteractions(identity);
  }
}
