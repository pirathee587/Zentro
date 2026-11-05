package com.zentro.notificationintegration.notification.template;

import java.util.List;

/** A non-throwing outcome from rendering a notification template. */
public record TemplateRenderResult(
    Outcome outcome,
    String templateId,
    String message,
    List<String> invalidVariables
) {
  public TemplateRenderResult {
    invalidVariables = invalidVariables == null ? List.of() : List.copyOf(invalidVariables);
  }

  public enum Outcome {
    RENDERED,
    VALIDATION_FAILURE,
    UNSUPPORTED_EVENT,
    SKIPPED
  }

  public static TemplateRenderResult rendered(String templateId, String message) {
    return new TemplateRenderResult(Outcome.RENDERED, templateId, message, List.of());
  }

  public static TemplateRenderResult validationFailure(String templateId, List<String> invalidVariables) {
    return new TemplateRenderResult(Outcome.VALIDATION_FAILURE, templateId, null, invalidVariables);
  }

  public static TemplateRenderResult unsupported(String eventType) {
    return new TemplateRenderResult(Outcome.UNSUPPORTED_EVENT, "unsupported:" + safeIdentifier(eventType), null,
        List.of());
  }

  public static TemplateRenderResult skipped(String templateId) {
    return new TemplateRenderResult(Outcome.SKIPPED, templateId, null, List.of());
  }

  private static String safeIdentifier(String value) {
    if (value == null) {
      return "missing";
    }
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }
}
