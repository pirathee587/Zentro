package com.zentro.notificationintegration.notification.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.zentro.notificationintegration.delivery.sms.MockSmsSender;
import com.zentro.notificationintegration.notification.recipient.RecipientResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChannelPreferenceServiceTest {
  private final ChannelPreferenceService service = new ChannelPreferenceService();
  private final RecipientResolver.ResolvedRecipient recipient =
      new RecipientResolver.ResolvedRecipient("ZT-ABCDEFGH12", RecipientResolver.RecipientType.PARCEL_TRACKING_REF);

  @Test
  @DisplayName("Default channel is MOCK_SMS when no preferences are specified")
  void defaultsToMockSmsWhenEmpty() {
    var decision = service.resolveChannel(recipient, List.of());
    assertThat(decision.isDeliverable()).isTrue();
    assertThat(decision.channel()).isEqualTo(MockSmsSender.CHANNEL);
    assertThat(decision.status()).isEqualTo(ChannelPreferenceService.ChannelStatus.SUPPORTED);

    var nullDecision = service.resolveChannel(recipient, null);
    assertThat(nullDecision.isDeliverable()).isTrue();
    assertThat(nullDecision.channel()).isEqualTo(MockSmsSender.CHANNEL);
  }

  @Test
  @DisplayName("Selects preferred channel if supported")
  void selectsPreferredSupportedChannel() {
    var decision = service.resolveChannel(recipient, List.of("mock_sms"));
    assertThat(decision.isDeliverable()).isTrue();
    assertThat(decision.channel()).isEqualTo(MockSmsSender.CHANNEL);
  }

  @Test
  @DisplayName("Falls back to next preferred channel when first is unsupported")
  void fallsBackToSupportedChannel() {
    var decision = service.resolveChannel(recipient, List.of("EMAIL", "PUSH", "MOCK_SMS"));
    assertThat(decision.isDeliverable()).isTrue();
    assertThat(decision.channel()).isEqualTo(MockSmsSender.CHANNEL);
  }

  @Test
  @DisplayName("Returns unsupported status when no preferred channels are supported")
  void returnsUnsupportedWhenNoneSupported() {
    var decision = service.resolveChannel(recipient, List.of("EMAIL", "PUSH"));
    assertThat(decision.isDeliverable()).isFalse();
    assertThat(decision.status()).isEqualTo(ChannelPreferenceService.ChannelStatus.UNSUPPORTED);
    assertThat(decision.channel()).isEqualTo("EMAIL");
  }

  @Test
  @DisplayName("Returns unsupported when recipient is null")
  void returnsUnsupportedWhenRecipientIsNull() {
    var decision = service.resolveChannel(null, List.of("MOCK_SMS"));
    assertThat(decision.isDeliverable()).isFalse();
    assertThat(decision.status()).isEqualTo(ChannelPreferenceService.ChannelStatus.UNSUPPORTED);
  }

  @Test
  @DisplayName("Verifies channel support correctly")
  void checksChannelSupport() {
    assertThat(service.isChannelSupported("MOCK_SMS")).isTrue();
    assertThat(service.isChannelSupported("mock_sms")).isTrue();
    assertThat(service.isChannelSupported("EMAIL")).isFalse();
    assertThat(service.isChannelSupported(null)).isFalse();
  }
}
