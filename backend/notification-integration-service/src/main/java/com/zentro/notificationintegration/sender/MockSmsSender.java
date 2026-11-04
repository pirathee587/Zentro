package com.zentro.notificationintegration.sender;

import com.zentro.notificationintegration.config.NotificationProperties;
import com.zentro.notificationintegration.config.NotificationProperties.FailMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stand-in for an SMS provider. "Sending" writes one INFO log line with the event id, tracking reference and
 * status only (never the message text). The fail mode makes it fail on purpose to test retries locally:
 * none (default), always, or first-attempt.
 */
@Component
public class MockSmsSender implements NotificationSender {
  public static final String CHANNEL = "MOCK_SMS";
  private static final Logger log = LoggerFactory.getLogger(MockSmsSender.class);

  private final FailMode failMode;

  @Autowired
  public MockSmsSender(NotificationProperties properties) {
    this(properties.mock().failMode());
  }

  public MockSmsSender(FailMode failMode) {
    this.failMode = failMode;
  }

  @Override
  public String channel() {
    return CHANNEL;
  }

  @Override
  public void send(OutgoingNotification notification) throws NotificationSendException {
    if (failMode == FailMode.ALWAYS
        || (failMode == FailMode.FIRST_ATTEMPT && notification.attemptNumber() == 1)) {
      throw new NotificationSendException("mock SMS provider unavailable (fail mode " + failMode + ")");
    }
    log.info("Mock SMS sent: eventId={} to=parcel {} status={} attempt={}",
        notification.eventId(), notification.trackingRef(), notification.parcelStatus(),
        notification.attemptNumber());
  }
}
