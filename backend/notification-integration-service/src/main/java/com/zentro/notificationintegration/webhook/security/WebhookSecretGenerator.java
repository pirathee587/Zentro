package com.zentro.notificationintegration.webhook.security;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Creates merchant signing secrets: 32 random bytes, shown once as "whsec_" + base64url (no padding). */
@Component
public class WebhookSecretGenerator {
  public static final String PREFIX = "whsec_";
  static final int SECRET_BYTES = 32;
  static final int HINT_LENGTH = 8;

  /**
   * @param value the full secret, returned to the merchant once and then only stored encrypted
   * @param hint  the first {@value #HINT_LENGTH} characters, safe to store and show
   */
  public record GeneratedSecret(String value, String hint) {
    @Override
    public String toString() {
      return "GeneratedSecret[hint=" + hint + "]";
    }
  }

  private final SecureRandom random = new SecureRandom();

  public GeneratedSecret generate() {
    byte[] bytes = new byte[SECRET_BYTES];
    random.nextBytes(bytes);
    String value = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return new GeneratedSecret(value, value.substring(0, HINT_LENGTH));
  }
}
