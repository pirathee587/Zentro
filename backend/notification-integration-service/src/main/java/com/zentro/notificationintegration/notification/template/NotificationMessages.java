package com.zentro.notificationintegration.notification.template;

import com.zentro.notificationintegration.delivery.sms.MockSmsSender;
import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import com.zentro.notificationintegration.messaging.event.ParcelEventParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Versionable, channel-specific notification templates and their input policy.
 * Only the approved parcel event contracts and the currently available mock SMS sender are supported.
 * User data is inserted as plain text after sanitizing; it is never parsed as template syntax.
 */
@Component
public class NotificationMessages {
  private static final Pattern TRACKING_REFERENCE = Pattern.compile("^ZT-[0-9A-HJKMNP-TV-Z]{10}$");
  private static final int MAX_REASON_INPUT = 200;
  private static final int MAX_REASON_OUTPUT = 140;
  private static final String CREATED_ID = "parcel.created.mock-sms.v1";
  private static final String STATUS_ID = "parcel.status.updated.mock-sms.v1";

  /** Template inventory is kept explicit so a new contract event cannot silently inherit generic wording. */
  private static final Map<String, Set<String>> TEMPLATES = Map.of(
      ParcelEventParser.PARCEL_CREATED, Set.of(MockSmsSender.CHANNEL),
      ParcelEventParser.PARCEL_STATUS_UPDATED, Set.of(MockSmsSender.CHANNEL));

  /** Renders an event for the existing notification channel without throwing on bad data. */
  public TemplateRenderResult render(ParcelEvent event, String channel) {
    if (event == null) {
      return invalid("unknown", List.of("event"));
    }
    return render(event.eventType(), event.status(), Map.of(
        "trackingRef", event.trackingRef() == null ? NullValue.INSTANCE : event.trackingRef(),
        "failureReason", event.failureReason() == null ? NullValue.INSTANCE : event.failureReason()), channel);
  }

  /** Map entry point keeps validation robust for malformed/wrongly typed values before event construction. */
  public TemplateRenderResult render(String eventType, Map<String, ?> variables, String channel) {
    if (variables == null) {
      return render(eventType, null, null, channel);
    }
    Object rawStatus = variables.get("status");
    if (!(rawStatus instanceof String status)) {
      String templateId = ParcelEventParser.PARCEL_CREATED.equals(eventType) ? CREATED_ID : STATUS_ID;
      if (eventType == null || !TEMPLATES.containsKey(eventType)) {
        return TemplateRenderResult.unsupported(eventType);
      }
      if (channel == null || !TEMPLATES.get(eventType).contains(channel)) {
        return TemplateRenderResult.unsupportedChannel(channel);
      }
      return invalid(templateId, List.of("status"));
    }
    return render(eventType, status, variables, channel);
  }

  /** Map entry point keeps validation robust for malformed/wrongly typed values before event construction. */
  public TemplateRenderResult render(String eventType, String status, Map<String, ?> variables, String channel) {
    Set<String> channels = eventType == null ? null : TEMPLATES.get(eventType);
    if (channels == null) {
      return TemplateRenderResult.unsupported(eventType);
    }
    if (channel == null || !channels.contains(channel)) {
      return TemplateRenderResult.unsupportedChannel(channel);
    }
    String templateId = ParcelEventParser.PARCEL_CREATED.equals(eventType) ? CREATED_ID : STATUS_ID;
    if (variables == null) {
      return invalid(templateId, List.of("trackingRef"));
    }
    if (!validStatus(eventType, status)) {
      return invalid(templateId, List.of("status"));
    }
    Object rawReference = variables.get("trackingRef");
    if (!(rawReference instanceof String trackingRef) || trackingRef.isBlank()
        || !TRACKING_REFERENCE.matcher(trackingRef).matches()) {
      return invalid(templateId, List.of("trackingRef"));
    }

    String cleanReference = sanitize(trackingRef, 13);
    if ("IN_TRANSIT".equals(status)) {
      return TemplateRenderResult.skipped(templateId);
    }
    if ("FAILED".equals(status)) {
      Object rawReason = variables.get("failureReason");
      if (!(rawReason instanceof String reason) || reason.isBlank() || reason.codePointCount(0, reason.length()) > MAX_REASON_INPUT) {
        return invalid(templateId, List.of("failureReason"));
      }
      String safeReason = truncate(sanitize(reason, MAX_REASON_INPUT), MAX_REASON_OUTPUT);
      if (safeReason.isBlank()) {
        return invalid(templateId, List.of("failureReason"));
      }
      return TemplateRenderResult.rendered(templateId,
          "Delivery of parcel " + cleanReference + " was not completed: " + safeReason + ". Please contact support for help.");
    }

    String text = switch (status) {
      case "CREATED" -> "Your SwiftDrop parcel " + cleanReference + " has been booked.";
      case "PICKED_UP" -> "Your parcel " + cleanReference + " has been picked up.";
      case "OUT_FOR_DELIVERY" -> "Your parcel " + cleanReference + " is out for delivery.";
      case "DELIVERED" -> "Your parcel " + cleanReference + " has been delivered.";
      default -> null;
    };
    if (text == null) {
      return TemplateRenderResult.skipped(templateId);
    }
    return TemplateRenderResult.rendered(templateId, text);
  }

  /** Exposed immutable inventory for completeness tests. */
  public static Map<String, Set<String>> templateInventory() {
    return TEMPLATES;
  }

  /** Contract-level field policy for each event template; failure adds its conditionally required reason. */
  public static Map<String, List<String>> requiredVariables() {
    return Map.of(
        CREATED_ID, List.of("trackingRef"),
        STATUS_ID, List.of("trackingRef"),
        STATUS_ID + ".FAILED", List.of("trackingRef", "failureReason"));
  }

  public static Map<String, List<String>> optionalVariables() {
    return Map.of(CREATED_ID, List.of(), STATUS_ID, List.of());
  }

  private static boolean validStatus(String eventType, String status) {
    if (status == null) {
      return false;
    }
    if (ParcelEventParser.PARCEL_CREATED.equals(eventType)) {
      return "CREATED".equals(status);
    }
    return Set.of("CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED")
        .contains(status);
  }

  private static TemplateRenderResult invalid(String templateId, List<String> variables) {
    return TemplateRenderResult.validationFailure(templateId, variables);
  }

  private static String sanitize(String value, int maxCodePoints) {
    StringBuilder clean = new StringBuilder();
    value.codePoints()
        .filter(cp -> Character.getType(cp) != Character.CONTROL && Character.getType(cp) != Character.FORMAT
            && cp != 0x7f)
        .limit(maxCodePoints)
        .forEach(clean::appendCodePoint);
    return clean.toString().strip();
  }

  private static String truncate(String value, int maxCodePoints) {
    int count = value.codePointCount(0, value.length());
    if (count <= maxCodePoints) {
      return value;
    }
    int end = value.offsetByCodePoints(0, Math.max(0, maxCodePoints - 1));
    return value.substring(0, end) + "…";
  }

  private enum NullValue { INSTANCE }
}
