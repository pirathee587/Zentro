package com.zentro.notificationintegration.notification.recipient;

import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves recipient destination from parcel events according to ZD-065 / D1.
 * Parcel events carry no personal contact details (names, phone numbers, addresses).
 * The recipient is resolved to the validated tracking reference ("ZT-" + 10 Crockford Base32 characters).
 */
@Component
public class RecipientResolver {
  private static final Pattern TRACKING_REFERENCE = Pattern.compile("^ZT-[0-9A-HJKMNP-TV-Z]{10}$");

  public record ResolvedRecipient(String destination, RecipientType type) {}

  public enum RecipientType {
    PARCEL_TRACKING_REF
  }

  /**
   * Resolves recipient from a parcel event.
   * Returns empty if event is null, tracking reference is missing or does not match contract format.
   */
  public Optional<ResolvedRecipient> resolve(ParcelEvent event) {
    if (event == null || event.trackingRef() == null) {
      return Optional.empty();
    }
    String trackingRef = event.trackingRef();
    if (!TRACKING_REFERENCE.matcher(trackingRef).matches()) {
      return Optional.empty();
    }
    return Optional.of(new ResolvedRecipient(trackingRef, RecipientType.PARCEL_TRACKING_REF));
  }
}
