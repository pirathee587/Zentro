package com.zentro.notificationintegration.notification.template;

import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Which parcel statuses notify the customer, and with what text. Simple fixed wording; managed templates are
 * #64. IN_TRANSIT does not notify (recorded as SKIPPED).
 */
@Component
public class NotificationMessages {

  /** The message for this event, or empty when this status does not notify anyone. */
  public Optional<String> messageFor(ParcelEvent event) {
    String ref = event.trackingRef();
    return Optional.ofNullable(switch (event.status()) {
      case "CREATED" -> "Your SwiftDrop parcel " + ref + " has been booked.";
      case "PICKED_UP" -> "Your parcel " + ref + " has been picked up.";
      case "OUT_FOR_DELIVERY" -> "Your parcel " + ref + " is out for delivery.";
      case "DELIVERED" -> "Your parcel " + ref + " has been delivered.";
      case "FAILED" -> "Delivery of parcel " + ref + " was not completed: " + event.failureReason() + ".";
      default -> null;
    });
  }
}
