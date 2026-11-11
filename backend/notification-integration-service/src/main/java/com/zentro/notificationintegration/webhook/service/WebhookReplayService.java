package com.zentro.notificationintegration.webhook.service;

import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository.ReplayResult;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookReplayService {
  private final WebhookDeliveryRepository deliveries;

  public WebhookReplayService(WebhookDeliveryRepository deliveries) {
    this.deliveries = deliveries;
  }

  @Transactional
  public ReplayResult replay(UUID deliveryId, String dispatcherSubject) {
    return deliveries.replayFailed(deliveryId, dispatcherSubject, WebhookDeliveryService.DISABLED_ERROR);
  }
}
