package com.zentro.dispatchcourier;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SubmitProofRequestTest {
  @Test void matchesBrowserTrimmingForNonbreakingWhitespaceAndBom() {
    assertThat(new SubmitProofRequest("\u00a0Nimal\ufeff", "\u202f\ufeff", "\u2007ref\u00a0").normalized())
        .isEqualTo(new SubmitProofRequest("Nimal", null, "ref"));
    assertThatThrownBy(() -> new SubmitProofRequest("\u00a0\ufeff", null, "ref").normalized())
        .isInstanceOf(ProofException.class);
  }
  @ParameterizedTest @ValueSource(ints = {0, 1, 2})
  void rejectsNullCharacterBeforePostgres(int field) {
    var request = new SubmitProofRequest(field == 0 ? "Nimal\0" : "Nimal",
        field == 1 ? "note\0" : null, field == 2 ? "ref\0" : "ref");
    assertThatThrownBy(request::normalized).isInstanceOfSatisfying(ProofException.class,
        failure -> assertThat(failure.status()).isEqualTo(400));
  }
  @Test void normalizesOptionalAndRequiredFields() {
    assertThat(new SubmitProofRequest("  Nimal  ", " note ", " ref ").normalized())
        .isEqualTo(new SubmitProofRequest("Nimal", "note", "ref"));
    assertThat(new SubmitProofRequest("Nimal", " \t\n", "ref").normalized().notes()).isNull();
  }
  @Test void acceptsExactLimitsAndUnicodeCharacters() {
    assertThat(new SubmitProofRequest("😀".repeat(100), "n".repeat(500), "r".repeat(200)).normalized().recipientName())
        .isEqualTo("😀".repeat(100));
  }
  @ParameterizedTest @ValueSource(ints = {0, 1, 2})
  void rejectsEachOversizeField(int field) {
    var request = new SubmitProofRequest(field == 0 ? "n".repeat(101) : "Nimal",
        field == 1 ? "n".repeat(501) : null, field == 2 ? "r".repeat(201) : "ref");
    assertThatThrownBy(request::normalized).isInstanceOf(ProofException.class);
  }
}
