package com.zentro.notificationintegration.delivery.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zentro.notificationintegration.config.NotificationProperties.FailMode;
import com.zentro.notificationintegration.delivery.provider.NotificationSendException;
import com.zentro.notificationintegration.delivery.provider.OutgoingNotification;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class MockSmsSenderTest {
  private static final String TEXT = "Delivery of parcel ZT-ABCDEFGH12 was not completed: Gate locked.";

  private static OutgoingNotification notification(int attempt) {
    return new OutgoingNotification(UUID.randomUUID(), "ZT-ABCDEFGH12", "FAILED", "MOCK_SMS", TEXT, attempt);
  }

  @Test
  @DisplayName("none: every attempt succeeds without logging recipient reference or message")
  void noneAlwaysSends() {
    Logger logger = (Logger) LoggerFactory.getLogger(MockSmsSender.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      MockSmsSender sender = new MockSmsSender(FailMode.NONE);
      assertThatCode(() -> sender.send(notification(1))).doesNotThrowAnyException();
      assertThat(logs.list).singleElement().satisfies(l -> {
        assertThat(l.getFormattedMessage()).contains("MOCK_SMS", "FAILED");
        assertThat(l.getFormattedMessage()).doesNotContain("ZT-ABCDEFGH12", "Gate locked", TEXT);
      });
    } finally {
      logger.detachAppender(logs);
    }
  }

  @Test
  @DisplayName("always: every attempt fails")
  void alwaysFails() {
    MockSmsSender sender = new MockSmsSender(FailMode.ALWAYS);
    assertThatThrownBy(() -> sender.send(notification(1))).isInstanceOf(NotificationSendException.class);
    assertThatThrownBy(() -> sender.send(notification(4))).isInstanceOf(NotificationSendException.class)
        .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("Gate locked"));
  }

  @Test
  @DisplayName("first-attempt: only the first attempt fails")
  void firstAttemptFailsOnce() {
    MockSmsSender sender = new MockSmsSender(FailMode.FIRST_ATTEMPT);
    assertThatThrownBy(() -> sender.send(notification(1))).isInstanceOf(NotificationSendException.class);
    assertThatCode(() -> sender.send(notification(2))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Channel is MOCK_SMS")
  void channel() {
    assertThat(new MockSmsSender(FailMode.NONE).channel()).isEqualTo("MOCK_SMS");
  }
}
