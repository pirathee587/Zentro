package com.zentro.dispatchcourier.pod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ProofImageValidatorTest {
  @Test void acceptsRecognizedImageBytesRegardlessOfFilename() {
    var image = new MockMultipartFile("photo", "evidence.txt", "text/plain", new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,0,0});
    assertThat(ProofImageValidator.validate(image)).isEqualTo("image/jpeg");
  }
  @Test void rejectsSpoofedOrOversizedContent() {
    var fake = new MockMultipartFile("photo", "proof.jpg", "image/jpeg", "not an image".getBytes());
    assertThatThrownBy(() -> ProofImageValidator.validate(fake)).hasMessageContaining("Check the delivery photo");
    var large = new MockMultipartFile("photo", "proof.png", "image/png", new byte[(int)ProofImageValidator.MAX_BYTES + 1]);
    assertThatThrownBy(() -> ProofImageValidator.validate(large)).hasMessageContaining("Check the delivery photo");
  }
}
