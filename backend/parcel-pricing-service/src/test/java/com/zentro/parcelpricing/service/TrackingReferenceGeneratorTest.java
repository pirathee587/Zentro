package com.zentro.parcelpricing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrackingReferenceGeneratorTest {

  @Test
  @DisplayName("Reference is ZT- followed by 10 Crockford Base32 characters")
  void referenceHasExpectedFormat() {
    TrackingReferenceGenerator generator = new TrackingReferenceGenerator();
    for (int i = 0; i < 1_000; i++) {
      assertThat(generator.next()).matches("^ZT-[0-9A-HJKMNP-TV-Z]{10}$");
    }
  }

  @Test
  @DisplayName("Reference never contains the ambiguous letters I, L, O or U")
  void referenceAvoidsAmbiguousLetters() {
    TrackingReferenceGenerator generator = new TrackingReferenceGenerator();
    for (int i = 0; i < 1_000; i++) {
      assertThat(generator.next().substring(3)).doesNotContain("I", "L", "O", "U");
    }
  }

  @Test
  @DisplayName("Many generated references are all different")
  void referencesAreDistinct() {
    TrackingReferenceGenerator generator = new TrackingReferenceGenerator();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      seen.add(generator.next());
    }
    assertThat(seen).hasSize(10_000);
  }

  @Test
  @DisplayName("Consecutive references are not sequential")
  void referencesAreNotSequential() {
    TrackingReferenceGenerator generator = new TrackingReferenceGenerator(new SplittableRandom(42));
    String first = generator.next();
    String second = generator.next();
    assertThat(second).isNotEqualTo(first);
    assertThat(second.substring(0, 12)).isNotEqualTo(first.substring(0, 12));
  }
}
