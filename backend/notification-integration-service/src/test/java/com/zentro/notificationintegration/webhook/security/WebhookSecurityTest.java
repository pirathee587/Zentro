package com.zentro.notificationintegration.webhook.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zentro.notificationintegration.webhook.config.WebhookProperties;
import com.zentro.notificationintegration.webhook.exception.WebhookSecretException;
import com.zentro.notificationintegration.webhook.exception.WebhooksUnavailableException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebhookSecurityTest {
  static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="; // test-only key: bytes 0..31
  static final String OTHER_KEY = "HxwdHh8AAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRo=";

  static WebhookProperties properties(boolean enabled, String key) {
    return new WebhookProperties(enabled, false, key, "merchant-webhooks",
        new WebhookProperties.Retry(8, 5000, 10000, 1800000, 2.0),
        new WebhookProperties.Http(2000, 5000),
        new WebhookProperties.Health(60, 10));
  }

  @Nested
  class Signer {
    private final WebhookSigner signer = new WebhookSigner();

    @Test
    @DisplayName("Signature is sha256=<hex HMAC-SHA256 of \"<timestamp>.<body>\"> (independently computed vector)")
    void knownVector() {
      assertThat(signer.sign("whsec_test-secret", 1762400000L, "{\"eventId\":\"e1\"}"))
          .isEqualTo("sha256=47bacc5f771f240148d5f141e54e320b6de24ef4d162e25eeaaf792c5ed7c7da");
    }

    @Test
    @DisplayName("Changing the body, the timestamp or the secret changes the signature")
    void anyChangeChangesSignature() {
      String base = signer.sign("whsec_a", 100, "{}");
      assertThat(signer.sign("whsec_a", 100, "{ }")).isNotEqualTo(base);
      assertThat(signer.sign("whsec_a", 101, "{}")).isNotEqualTo(base);
      assertThat(signer.sign("whsec_b", 100, "{}")).isNotEqualTo(base);
      assertThat(base).matches("sha256=[0-9a-f]{64}");
    }
  }

  @Nested
  class Generator {
    private final WebhookSecretGenerator generator = new WebhookSecretGenerator();

    @Test
    @DisplayName("Secrets are whsec_ + base64url of 32 random bytes, unique, with an 8-character hint")
    void format() {
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < 50; i++) {
        WebhookSecretGenerator.GeneratedSecret secret = generator.generate();
        assertThat(secret.value()).matches("whsec_[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(secret.value().substring(6))).hasSize(32);
        assertThat(secret.hint()).hasSize(8).isEqualTo(secret.value().substring(0, 8));
        assertThat(seen.add(secret.value())).isTrue();
      }
    }

    @Test
    @DisplayName("toString shows only the hint, never the secret")
    void toStringHidesSecret() {
      WebhookSecretGenerator.GeneratedSecret secret = generator.generate();
      assertThat(secret.toString()).doesNotContain(secret.value()).contains(secret.hint());
    }
  }

  @Nested
  class Cipher {
    private final WebhookSecretCipher cipher = new WebhookSecretCipher(properties(true, KEY));

    @Test
    @DisplayName("Encrypt then decrypt returns the secret; the stored value never contains it")
    void roundTrip() {
      String stored = cipher.encrypt("whsec_plain-secret", "merchant-1");

      assertThat(stored).startsWith("v1.").doesNotContain("whsec_plain-secret");
      assertThat(cipher.decrypt(stored, "merchant-1")).isEqualTo("whsec_plain-secret");
    }

    @Test
    @DisplayName("Each encryption uses a fresh IV, so the same secret gives different ciphertexts")
    void freshIv() {
      assertThat(cipher.encrypt("whsec_x", "m")).isNotEqualTo(cipher.encrypt("whsec_x", "m"));
    }

    @Test
    @DisplayName("A ciphertext bound to one merchant cannot be decrypted for another")
    void boundToMerchant() {
      String stored = cipher.encrypt("whsec_plain-secret", "merchant-1");
      assertThatThrownBy(() -> cipher.decrypt(stored, "merchant-2")).isInstanceOf(WebhookSecretException.class);
    }

    @Test
    @DisplayName("Tampered data, an unknown format or a different key are refused without echoing anything")
    void tamperedOrWrongKey() {
      String stored = cipher.encrypt("whsec_plain-secret", "merchant-1");
      byte[] raw = Base64.getDecoder().decode(stored.substring(3));
      raw[raw.length - 1] ^= 1;
      String tampered = "v1." + Base64.getEncoder().encodeToString(raw);

      assertThatThrownBy(() -> cipher.decrypt(tampered, "merchant-1")).isInstanceOf(WebhookSecretException.class)
          .hasMessageNotContaining("whsec_");
      assertThatThrownBy(() -> cipher.decrypt("plain-text", "merchant-1")).isInstanceOf(WebhookSecretException.class);
      assertThatThrownBy(() -> cipher.decrypt("v1.%%%", "merchant-1")).isInstanceOf(WebhookSecretException.class);
      assertThatThrownBy(() -> cipher.decrypt("v1.AAAA", "merchant-1")).isInstanceOf(WebhookSecretException.class);
      WebhookSecretCipher other = new WebhookSecretCipher(properties(true, OTHER_KEY));
      assertThatThrownBy(() -> other.decrypt(stored, "merchant-1")).isInstanceOf(WebhookSecretException.class);
    }

    @Test
    @DisplayName("Enabled without a key, or with a key that is not 32 bytes of base64, fails at start-up")
    void invalidKeyFailsFast() {
      assertThatThrownBy(() -> new WebhookSecretCipher(properties(true, "")))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("WEBHOOK_SECRET_KEY must be set");
      assertThatThrownBy(() -> new WebhookSecretCipher(properties(false, "not base64!")))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("not valid base64");
      assertThatThrownBy(() -> new WebhookSecretCipher(properties(false, "AAECAwQ=")))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly 32 bytes");
    }

    @Test
    @DisplayName("Disabled without a key: the service starts, but encrypting is refused (503)")
    void disabledWithoutKey() {
      WebhookSecretCipher none = new WebhookSecretCipher(properties(false, ""));
      assertThat(none.isConfigured()).isFalse();
      assertThatThrownBy(() -> none.encrypt("whsec_x", "m")).isInstanceOf(WebhooksUnavailableException.class);
    }

    @Test
    @DisplayName("The settings never print the key")
    void propertiesHideKey() {
      assertThat(properties(true, KEY).toString()).doesNotContain(KEY).contains("secretKey=<set>");
      assertThat(properties(false, "").toString()).contains("secretKey=<not set>");
    }
  }
}
