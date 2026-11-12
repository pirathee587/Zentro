package com.zentro.notificationintegration.notification.recipient;

import static org.assertj.core.api.Assertions.assertThat;

import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecipientResolverTest {
  private final RecipientResolver resolver = new RecipientResolver();

  private static ParcelEvent eventWithRef(String trackingRef) {
    return new ParcelEvent(
        UUID.randomUUID(),
        "parcel.created",
        UUID.randomUUID(),
        OffsetDateTime.now(),
        trackingRef,
        "CREATED",
        null
    );
  }

  @Test
  @DisplayName("Resolves recipient successfully for valid ZT- tracking reference")
  void resolvesValidTrackingReference() {
    var result = resolver.resolve(eventWithRef("ZT-ABCDEFGH12"));
    assertThat(result).isPresent();
    assertThat(result.get().destination()).isEqualTo("ZT-ABCDEFGH12");
    assertThat(result.get().type()).isEqualTo(RecipientResolver.RecipientType.PARCEL_TRACKING_REF);
  }

  @Test
  @DisplayName("Returns empty for null event")
  void returnsEmptyForNullEvent() {
    assertThat(resolver.resolve(null)).isEmpty();
  }

  @Test
  @DisplayName("Returns empty for null tracking reference")
  void returnsEmptyForNullTrackingReference() {
    assertThat(resolver.resolve(eventWithRef(null))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "",
      "   ",
      "ZT-INVALID",
      "ZT-123",
      "ABCDEFGH12",
      "zt-abcdefgh12",
      "ZT-ABCDEFGH123",
      "ZT-ABCDEFGH1I", // 'I' is not Crockford Base32
      "ZT-ABCDEFGH1L", // 'L' is not Crockford Base32
      "ZT-ABCDEFGH1O", // 'O' is not Crockford Base32
      "ZT-ABCDEFGH1U", // 'U' is not Crockford Base32
      "<script>alert(1)</script>",
      "ZT-ABCDEFGH12\n"
  })
  @DisplayName("Rejects malformed, invalid format, or injection strings as tracking reference")
  void rejectsInvalidTrackingReferences(String invalidRef) {
    assertThat(resolver.resolve(eventWithRef(invalidRef))).isEmpty();
  }
}
