package com.zentro.notificationintegration.notification.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NotificationMessagesTest {
  private final NotificationMessages messages = new NotificationMessages();

  private static ParcelEvent event(String status, String reason) {
    return new ParcelEvent(UUID.randomUUID(), "parcel.status.updated", UUID.randomUUID(), OffsetDateTime.now(),
        "ZT-ABCDEFGH12", status, reason);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "CREATED|Your SwiftDrop parcel ZT-ABCDEFGH12 has been booked.",
      "PICKED_UP|Your parcel ZT-ABCDEFGH12 has been picked up.",
      "OUT_FOR_DELIVERY|Your parcel ZT-ABCDEFGH12 is out for delivery.",
      "DELIVERED|Your parcel ZT-ABCDEFGH12 has been delivered.",
  })
  @DisplayName("Notified statuses get their fixed message")
  void notifiedStatuses(String status, String expected) {
    assertThat(messages.messageFor(event(status, null))).contains(expected);
  }

  @org.junit.jupiter.api.Test
  @DisplayName("FAILED includes the failure reason")
  void failedIncludesReason() {
    assertThat(messages.messageFor(event("FAILED", "Recipient not available")))
        .contains("Delivery of parcel ZT-ABCDEFGH12 was not completed: Recipient not available.");
  }

  @org.junit.jupiter.api.Test
  @DisplayName("IN_TRANSIT does not notify")
  void inTransitIsSkipped() {
    assertThat(messages.messageFor(event("IN_TRANSIT", null))).isEmpty();
  }
}
