package com.zentro.identitypartner.user.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.identitypartner.user.exception.InvalidUserInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class AdminUpdateUserRequestTest {
  /** Same defaults as the application (unknown properties are ignored globally). */
  private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

  private AdminUpdateUserRequest read(String json) throws Exception {
    return mapper.readValue(json, AdminUpdateUserRequest.class);
  }

  private static InvalidUserInputException cause(Throwable error) {
    Throwable current = error;
    while (current != null && !(current instanceof InvalidUserInputException)) {
      current = current.getCause();
    }
    assertThat(current).as("InvalidUserInputException in the cause chain").isNotNull();
    return (InvalidUserInputException) current;
  }

  @Test
  @DisplayName("Reads the four allowed fields")
  void readsAllowedFields() throws Exception {
    AdminUpdateUserRequest request =
        read("{\"name\":\"Nimal\",\"phone\":\"0771234567\",\"accountStatus\":\"disabled\",\"reason\":\"Fraud check\"}");

    assertThat(request).isEqualTo(new AdminUpdateUserRequest("Nimal", "0771234567", "disabled", "Fraud check"));
    assertThat(request.hasUpdatableField()).isTrue();
  }

  @Test
  @DisplayName("An empty object or only a reason has no updatable field")
  void emptyRequest() throws Exception {
    assertThat(read("{}").hasUpdatableField()).isFalse();
    assertThat(read("{\"reason\":\"why\"}").hasUpdatableField()).isFalse();
    assertThat(read("{\"name\":null}").hasUpdatableField()).isFalse();
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "roles, PROTECTED_FIELD",
      "ROLES, PROTECTED_FIELD",
      "email, PROTECTED_FIELD",
      "keycloakSubject, PROTECTED_FIELD",
      "keycloak_subject, PROTECTED_FIELD",
      "accountType, PROTECTED_FIELD",
      "enabled, PROTECTED_FIELD",
      "id, PROTECTED_FIELD",
      "nickname, UNKNOWN_FIELD",
      "Name, UNKNOWN_FIELD",
  })
  @DisplayName("Any other field is rejected and named, even though unknown fields are ignored globally")
  void otherFieldsAreRejected(String field, String code) {
    assertThatThrownBy(() -> read("{\"name\":\"Nimal\",\"" + field + "\":[\"DISPATCHER\"]}"))
        .satisfies(error -> {
          InvalidUserInputException rejected = cause(error);
          assertThat(rejected.field()).isEqualTo(field);
          assertThat(rejected.code()).isEqualTo(code);
          assertThat(rejected.getMessage()).contains("'" + field + "'");
        });
  }

  @Test
  @DisplayName("A field name shown back is limited to safe characters")
  void unsafeFieldNameIsCleaned() {
    assertThatThrownBy(() -> read("{\"<script>x\":1}"))
        .satisfies(error -> assertThat(cause(error).field()).isEqualTo("scriptx"));
  }

  @Test
  @DisplayName("Non-text values are rejected for the named field")
  void nonTextValue() {
    assertThatThrownBy(() -> read("{\"accountStatus\":1}"))
        .satisfies(error -> {
          assertThat(cause(error).field()).isEqualTo("accountStatus");
          assertThat(cause(error).getMessage()).isEqualTo("accountStatus must be a text value.");
        });
  }
}
