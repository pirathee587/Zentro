package com.zentro.notificationintegration.webhook.event;

import com.zentro.notificationintegration.messaging.event.ParcelEvent;

/**
 * A validated parcel event plus what webhook delivery needs on top of it.
 *
 * @param merchantId  payload.merchantId; null for customer bookings (nothing is sent)
 * @param rawEnvelope the event envelope exactly as received, sent to the merchant unchanged
 */
public record MerchantParcelEvent(ParcelEvent event, String merchantId, String rawEnvelope) {
  @Override
  public String toString() {
    return "MerchantParcelEvent[eventId=" + event.eventId() + ", eventType=" + event.eventType()
        + ", merchantId=" + merchantId + "]";
  }
}
