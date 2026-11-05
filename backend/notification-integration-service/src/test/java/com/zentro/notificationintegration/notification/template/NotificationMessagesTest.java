package com.zentro.notificationintegration.notification.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.zentro.notificationintegration.delivery.sms.MockSmsSender;
import com.zentro.notificationintegration.messaging.event.ParcelEventParser;
import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class NotificationMessagesTest {
  private final NotificationMessages messages = new NotificationMessages();
  private static final String REF = "ZT-ABCDEFGH12";
  private static final String CREATED = ParcelEventParser.PARCEL_CREATED;
  private static final String UPDATED = ParcelEventParser.PARCEL_STATUS_UPDATED;

  @Test
  void everyApprovedEventHasEverySupportedChannelAndDeclaredVariables() {
    assertThat(NotificationMessages.templateInventory())
        .containsOnlyKeys(ParcelEventParser.supportedEventTypes().toArray(String[]::new))
        .containsOnlyKeys(CREATED, UPDATED)
        .allSatisfy((event, channels) -> assertThat(channels).containsExactly(MockSmsSender.CHANNEL));
    assertThat(NotificationMessages.requiredVariables().values()).allSatisfy(vars -> assertThat(vars).contains("trackingRef"));
    assertThat(NotificationMessages.requiredVariables().get("parcel.status.updated.mock-sms.v1.FAILED"))
        .containsExactly("trackingRef", "failureReason");
    assertThat(NotificationMessages.optionalVariables()).containsKeys(
        "parcel.created.mock-sms.v1", "parcel.status.updated.mock-sms.v1");
  }

  @Test
  void rendersEveryNotifiedEventWithExactMockSmsText() {
    assertRendered(CREATED, "CREATED", "Your SwiftDrop parcel " + REF + " has been booked.");
    assertRendered(UPDATED, "CREATED", "Your SwiftDrop parcel " + REF + " has been booked.");
    assertRendered(UPDATED, "PICKED_UP", "Your parcel " + REF + " has been picked up.");
    assertRendered(UPDATED, "OUT_FOR_DELIVERY", "Your parcel " + REF + " is out for delivery.");
    assertRendered(UPDATED, "DELIVERED", "Your parcel " + REF + " has been delivered.");
    assertRendered(UPDATED, "FAILED", "Delivery of parcel " + REF
        + " was not completed: Recipient not available. Please contact support for help.",
        Map.of("trackingRef", REF, "failureReason", "Recipient not available"));
  }

  @Test
  void inTransitRemainsSkippedAndUnsupportedInputIsSafe() {
    assertThat(render(UPDATED, "IN_TRANSIT", Map.of("trackingRef", REF)).outcome())
        .isEqualTo(TemplateRenderResult.Outcome.SKIPPED);
    assertThat(messages.render("parcel.deleted", "DELETED", Map.of(), MockSmsSender.CHANNEL).outcome())
        .isEqualTo(TemplateRenderResult.Outcome.UNSUPPORTED_EVENT);
    assertThat(messages.render(null, null, null, MockSmsSender.CHANNEL).outcome())
        .isEqualTo(TemplateRenderResult.Outcome.UNSUPPORTED_EVENT);
    assertThat(messages.render((ParcelEvent) null, MockSmsSender.CHANNEL).outcome())
        .isEqualTo(TemplateRenderResult.Outcome.VALIDATION_FAILURE);
    assertThat(messages.render(CREATED, "CREATED", null, MockSmsSender.CHANNEL).invalidVariables())
        .containsExactly("trackingRef");
  }

  @Test
  void validatesRequiredValuesWithoutReturningTheirContents() {
    for (Object value : new Object[] {null, "", "  ", 12, "ZT-invalid", "ZT-ABCDEFGH123"}) {
      TemplateRenderResult result = messages.render(CREATED, "CREATED",
          Map.of("trackingRef", value == null ? NullValue.INSTANCE : value), MockSmsSender.CHANNEL);
      assertThat(result.outcome()).isEqualTo(TemplateRenderResult.Outcome.VALIDATION_FAILURE);
      assertThat(result.invalidVariables()).containsExactly("trackingRef");
      assertThat(result.toString()).doesNotContain("ZT-invalid", "ABCDEFGH", "12");
    }
    for (Object reason : new Object[] {null, "", "  ", 10, "x".repeat(201)}) {
      Map<String, Object> vars = Map.of("trackingRef", REF,
          "failureReason", reason == null ? NullValue.INSTANCE : reason);
      TemplateRenderResult result = render(UPDATED, "FAILED", vars);
      assertThat(result.outcome()).isEqualTo(TemplateRenderResult.Outcome.VALIDATION_FAILURE);
      assertThat(result.invalidVariables()).containsExactly("failureReason");
      assertThat(result.toString()).doesNotContain("x".repeat(20));
    }
    assertThat(messages.render(CREATED, Map.of("status", 123, "trackingRef", REF), MockSmsSender.CHANNEL)
        .invalidVariables()).containsExactly("status");
  }

  @Test
  void sanitizesSmsTextAndAddsVisibleTruncationMarker() {
    String hostile = "<script>alert(1)</script> \\\"'><img src=x onerror=alert(1)> {{x}} ${x} #{x} %s\r\n\u0000\u200B\u202E";
    TemplateRenderResult safe = render(UPDATED, "FAILED", Map.of("trackingRef", REF, "failureReason", hostile));
    assertThat(safe.outcome()).isEqualTo(TemplateRenderResult.Outcome.RENDERED);
    assertThat(safe.message()).contains("<script>alert(1)</script>", "{{x}}", "${x}", "#{x}", "%s");
    assertThat(safe.message()).doesNotContain("\r", "\n", "\u0000", "\u200B", "\u202E");
    assertThat(safe.message()).doesNotContain("Your parcel ZT-ABCDEFGH12 was");

    String limit = "r".repeat(200);
    String truncated = render(UPDATED, "FAILED", Map.of("trackingRef", REF, "failureReason", limit)).message();
    assertThat(truncated).contains("r".repeat(139) + "…");
    assertThat(truncated).doesNotContain("r".repeat(141));
  }

  @Test
  void sameInputIsDeterministicAndSafeForConcurrentRendering() {
    TemplateRenderResult expected = render(UPDATED, "FAILED", Map.of("trackingRef", REF, "failureReason", "A < B"));
    assertThat(IntStream.range(0, 100).parallel()
        .mapToObj(i -> messages.render(UPDATED, "FAILED", Map.of("trackingRef", REF, "failureReason", "A < B"),
            MockSmsSender.CHANNEL))
        .allMatch(expected::equals)).isTrue();
  }

  private void assertRendered(String eventType, String status, String expected) {
    assertRendered(eventType, status, expected, Map.of("trackingRef", REF));
  }

  private void assertRendered(String eventType, String status, String expected, Map<String, ?> vars) {
    TemplateRenderResult result = messages.render(eventType, status, vars, MockSmsSender.CHANNEL);
    assertThat(result.outcome()).isEqualTo(TemplateRenderResult.Outcome.RENDERED);
    assertThat(result.message()).isEqualTo(expected);
  }

  private TemplateRenderResult render(String eventType, String status, Map<String, ?> variables) {
    return messages.render(eventType, status, variables, MockSmsSender.CHANNEL);
  }

  private enum NullValue { INSTANCE }
}
