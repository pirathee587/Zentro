package com.zentro.identitypartner.user.validation;

import com.zentro.identitypartner.user.exception.InvalidUserInputException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Input rules for dispatcher user management. Each method returns the normalised value or throws
 * {@link InvalidUserInputException} naming the field; messages never repeat the submitted value.
 *
 * <p>The phone rule is the same as S2's booking rule ({@code CreateParcelBookingRequest.PHONE_PATTERN}): a Sri
 * Lankan number, +94 or 0 followed by 9 digits, spaces and dashes ignored. It is copied, not imported, because
 * services never share internal classes.
 */
public final class UserInputRules {
  public static final List<String> ACCOUNT_STATUSES = List.of("ACTIVE", "DISABLED", "SUSPENDED");
  public static final int NAME_MAX = 200;
  public static final int REASON_MAX = 500;
  public static final int SEARCH_MAX = 100;
  public static final String PHONE_MESSAGE = "Enter a Sri Lankan number, e.g. 0771234567 or +94771234567.";

  private static final Pattern PHONE = Pattern.compile("^(\\+94|0)\\d{9}$");
  private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s-]");
  /** Control characters (C0, DEL, C1: Unicode category Cc) and line/paragraph separators. */
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");
  private static final Pattern CONTROL_EXCEPT_NEWLINE_TAB = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]|[\\u2028\\u2029]");

  private UserInputRules() {
  }

  /** Trimmed name of 1-200 characters without control characters. */
  public static String name(String raw) {
    String value = raw == null ? "" : raw.strip();
    if (value.isEmpty()) {
      throw new InvalidUserInputException("name", "Name must not be blank.");
    }
    if (value.length() > NAME_MAX) {
      throw new InvalidUserInputException("name", "Name must not exceed " + NAME_MAX + " characters.");
    }
    if (CONTROL.matcher(value).find()) {
      throw new InvalidUserInputException("name", "Name must not contain control characters.");
    }
    return value;
  }

  /** Sri Lankan phone number, returned without spaces and dashes, e.g. "+94771234567" or "0771234567". */
  public static String phone(String raw) {
    String value = raw == null ? "" : PHONE_SEPARATORS.matcher(raw.strip()).replaceAll("");
    if (value.isEmpty()) {
      throw new InvalidUserInputException("phone", "Phone must not be blank.");
    }
    if (!PHONE.matcher(value).matches()) {
      throw new InvalidUserInputException("phone", PHONE_MESSAGE);
    }
    return value;
  }

  /** ACTIVE, DISABLED or SUSPENDED, case-insensitive; returned in upper case. */
  public static String accountStatus(String raw, String field) {
    String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    if (!ACCOUNT_STATUSES.contains(value)) {
      String label = "accountStatus".equals(field) ? "account status" : field;
      throw new InvalidUserInputException(field, "Invalid " + label + ": must be ACTIVE, DISABLED or SUSPENDED.");
    }
    return value;
  }

  /** Optional reason: trimmed, at most 500 characters, no control characters except new lines and tabs. */
  public static String reason(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.strip();
    if (value.length() > REASON_MAX) {
      throw new InvalidUserInputException("reason", "Reason must not exceed " + REASON_MAX + " characters.");
    }
    if (CONTROL_EXCEPT_NEWLINE_TAB.matcher(value).find()) {
      throw new InvalidUserInputException("reason", "Reason must not contain control characters.");
    }
    return value;
  }

  /** Optional list status filter: null when absent, otherwise a valid status (else 400). */
  public static String statusFilter(String raw) {
    return raw == null || raw.isBlank() ? null : accountStatus(raw, "status");
  }

  /** Optional search text: trimmed, lower case, at most 100 characters; null when absent. */
  public static String search(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.strip();
    if (value.length() > SEARCH_MAX) {
      throw new InvalidUserInputException("search", "Search must not exceed " + SEARCH_MAX + " characters.");
    }
    if (CONTROL.matcher(value).find()) {
      throw new InvalidUserInputException("search", "Search must not contain control characters.");
    }
    return value.toLowerCase(Locale.ROOT);
  }

  /** A LIKE "contains" pattern in which %, _ and the escape character match literally (use ESCAPE '\'). */
  public static String containsPattern(String text) {
    String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
