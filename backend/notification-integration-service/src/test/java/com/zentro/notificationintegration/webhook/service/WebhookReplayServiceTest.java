package com.zentro.notificationintegration.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository.ReplayResult;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WebhookReplayServiceTest {

  private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
  private final WebhookReplayService service = new WebhookReplayService(deliveries);

  @Test
  @DisplayName("Replay delegates to repository with delivery ID, dispatcher subject, and disabled error marker")
  void replayDelegatesToRepository() {
    UUID deliveryId = UUID.randomUUID();
    String dispatcher = "dispatcher-test-sub";

    when(deliveries.replayFailed(deliveryId, dispatcher, WebhookDeliveryService.DISABLED_ERROR))
        .thenReturn(ReplayResult.REPLAYED);

    ReplayResult result = service.replay(deliveryId, dispatcher);

    assertThat(result).isEqualTo(ReplayResult.REPLAYED);
    verify(deliveries).replayFailed(deliveryId, dispatcher, WebhookDeliveryService.DISABLED_ERROR);
  }

  @ParameterizedTest
  @EnumSource(ReplayResult.class)
  @DisplayName("Replay propagates all ReplayResult enum values transparently")
  void replayPropagatesAllResults(ReplayResult expected) {
    UUID deliveryId = UUID.randomUUID();
    String dispatcher = "dispatcher-test-sub";

    when(deliveries.replayFailed(deliveryId, dispatcher, WebhookDeliveryService.DISABLED_ERROR))
        .thenReturn(expected);

    ReplayResult actual = service.replay(deliveryId, dispatcher);

    assertThat(actual).isEqualTo(expected);
  }
}
