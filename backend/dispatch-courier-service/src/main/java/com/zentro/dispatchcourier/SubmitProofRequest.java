package com.zentro.dispatchcourier;

import java.util.LinkedHashMap;
import java.util.Map;

/** Proposed #59 evidence contract; limits also enforced by PostgreSQL and the courier form. */
public record SubmitProofRequest(String recipientName, String notes, String proofReference) {
  // Match JavaScript String.trim(), including nonbreaking spaces and BOM, for identical replay semantics.
  private static final java.util.regex.Pattern EDGE_WHITESPACE = java.util.regex.Pattern.compile(
      "^[\\s\\p{Zs}\\u2028\\u2029\\uFEFF]+|[\\s\\p{Zs}\\u2028\\u2029\\uFEFF]+$");
  @Override public String toString() { return "SubmitProofRequest[redacted]"; }
  public SubmitProofRequest normalized() {
    var clean = new SubmitProofRequest(clean(recipientName), clean(notes), clean(proofReference));
    Map<String, String> errors = new LinkedHashMap<>();
    validate(errors, "recipientName", clean.recipientName(), 100, true);
    validate(errors, "notes", clean.notes(), 500, false);
    validate(errors, "proofReference", clean.proofReference(), 200, true);
    if (!errors.isEmpty()) throw new ProofException(400, "INVALID_PROOF", "Check the proof fields.", errors);
    return clean;
  }

  private static String clean(String value) {
    if (value == null) return null;
    String trimmed = EDGE_WHITESPACE.matcher(value).replaceAll("");
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static void validate(Map<String, String> errors, String field, String value, int max, boolean required) {
    if (required && value == null) errors.put(field, "This field is required.");
    else if (value != null && value.indexOf('\0') >= 0)
      errors.put(field, "The null character is not allowed.");
    else if (value != null && value.codePointCount(0, value.length()) > max)
      errors.put(field, "Use at most " + max + " characters.");
  }
}
