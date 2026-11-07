package com.zentro.identitypartner.user.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zentro.identitypartner.user.exception.InvalidUserInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UserInputRulesTest {

  private static void rejected(Runnable call, String field, String message) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(InvalidUserInputException.class, error -> {
          assertThat(error.field()).isEqualTo(field);
          assertThat(error.getMessage()).isEqualTo(message);
        });
  }

  @Test
  @DisplayName("Names are trimmed and limited to 200 characters without control characters")
  void names() {
    assertThat(UserInputRules.name("  Nimal Perera  ")).isEqualTo("Nimal Perera");
    assertThat(UserInputRules.name("A".repeat(200))).hasSize(200);
    rejected(() -> UserInputRules.name("   "), "name", "Name must not be blank.");
    rejected(() -> UserInputRules.name("A".repeat(201)), "name", "Name must not exceed 200 characters.");
    rejected(() -> UserInputRules.name("Nimal\nPerera"), "name", "Name must not contain control characters.");
    rejected(() -> UserInputRules.name("Nimal\u0000"), "name", "Name must not contain control characters.");
    rejected(() -> UserInputRules.name("Nim al"), "name", "Name must not contain control characters.");
    rejected(() -> UserInputRules.name("Nimal\u0085Perera"), "name", "Name must not contain control characters.");
  }

  @ParameterizedTest(name = "\"{0}\" -> {1}")
  @CsvSource({
      "0771234567, 0771234567",
      "+94771234567, +94771234567",
      "'  077 123 4567 ', 0771234567",
      "+94 77-123-4567, +94771234567",
  })
  @DisplayName("Sri Lankan phone numbers are accepted and stored without spaces or dashes")
  void validPhones(String raw, String expected) {
    assertThat(UserInputRules.phone(raw)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"077123456", "07712345678", "+1 555 0001", "94771234567", "077-ABC-4567", "+9477123456"})
  @DisplayName("Other phone formats are rejected with the same message as S2 bookings")
  void invalidPhones(String raw) {
    rejected(() -> UserInputRules.phone(raw), "phone", UserInputRules.PHONE_MESSAGE);
  }

  @Test
  @DisplayName("A blank phone is rejected")
  void blankPhone() {
    rejected(() -> UserInputRules.phone(" "), "phone", "Phone must not be blank.");
  }

  @Test
  @DisplayName("Account status is case-insensitive and normalised; anything else is rejected")
  void accountStatus() {
    assertThat(UserInputRules.accountStatus(" suspended ", "accountStatus")).isEqualTo("SUSPENDED");
    assertThat(UserInputRules.accountStatus("Active", "accountStatus")).isEqualTo("ACTIVE");
    rejected(() -> UserInputRules.accountStatus("SUPERUSER", "accountStatus"), "accountStatus",
        "Invalid account status: must be ACTIVE, DISABLED or SUSPENDED.");
    rejected(() -> UserInputRules.accountStatus("ADMIN", "accountStatus"), "accountStatus",
        "Invalid account status: must be ACTIVE, DISABLED or SUSPENDED.");
  }

  @Test
  @DisplayName("Reason is optional, trimmed, at most 500 characters; new lines and tabs are allowed")
  void reason() {
    assertThat(UserInputRules.reason(null)).isNull();
    assertThat(UserInputRules.reason("  ")).isNull();
    assertThat(UserInputRules.reason(" Repeated\nfailed\tdeliveries ")).isEqualTo("Repeated\nfailed\tdeliveries");
    assertThat(UserInputRules.reason("r".repeat(500))).hasSize(500);
    rejected(() -> UserInputRules.reason("r".repeat(501)), "reason", "Reason must not exceed 500 characters.");
    rejected(() -> UserInputRules.reason("bad\u0007bell"), "reason", "Reason must not contain control characters.");
  }

  @Test
  @DisplayName("The list status filter is optional but must be a valid status when given")
  void statusFilter() {
    assertThat(UserInputRules.statusFilter(null)).isNull();
    assertThat(UserInputRules.statusFilter("")).isNull();
    assertThat(UserInputRules.statusFilter("disabled")).isEqualTo("DISABLED");
    rejected(() -> UserInputRules.statusFilter("ARCHIVED"), "status",
        "Invalid status: must be ACTIVE, DISABLED or SUSPENDED.");
  }

  @Test
  @DisplayName("Search is optional, lower-cased and at most 100 characters")
  void search() {
    assertThat(UserInputRules.search(null)).isNull();
    assertThat(UserInputRules.search("  Nimal@Zentro.TEST ")).isEqualTo("nimal@zentro.test");
    assertThat(UserInputRules.search("s".repeat(100))).hasSize(100);
    rejected(() -> UserInputRules.search("s".repeat(101)), "search", "Search must not exceed 100 characters.");
    rejected(() -> UserInputRules.search("a\u0000b"), "search", "Search must not contain control characters.");
  }

  @Test
  @DisplayName("LIKE wildcards and the escape character are escaped so they match literally")
  void containsPattern() {
    assertThat(UserInputRules.containsPattern("50%_off")).isEqualTo("%50\\%\\_off%");
    assertThat(UserInputRules.containsPattern("a\\b")).isEqualTo("%a\\\\b%");
    assertThat(UserInputRules.containsPattern("nimal")).isEqualTo("%nimal%");
  }
}
