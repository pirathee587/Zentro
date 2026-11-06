package com.zentro.notificationintegration.webhook.security;

import com.zentro.notificationintegration.webhook.config.WebhookProperties;
import com.zentro.notificationintegration.webhook.exception.WebhookSecretException;
import com.zentro.notificationintegration.webhook.exception.WebhooksUnavailableException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts signing secrets with AES-256-GCM before they are stored. Secrets are encrypted, not hashed, because
 * S5 needs the plain value to sign deliveries. The merchant id is bound as associated data, so a ciphertext
 * copied to another merchant's row cannot be decrypted.
 *
 * <p>Stored format: {@code v1.} + base64(12-byte IV + ciphertext + 16-byte tag). The key comes from
 * {@code WEBHOOK_SECRET_KEY} (base64, 32 bytes). Start-up fails when webhooks are enabled without a valid key, or
 * when a key is set but invalid. Without a key and with webhooks disabled, every use is refused (503).
 */
@Component
public class WebhookSecretCipher {
  static final String VERSION = "v1.";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  public WebhookSecretCipher(WebhookProperties properties) {
    this.key = parseKey(properties.secretKey(), properties.enabled());
  }

  /** True when an encryption key is configured. */
  public boolean isConfigured() {
    return key != null;
  }

  public String encrypt(String secret, String merchantId) {
    SecretKey k = requireKey();
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(merchantId.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
      byte[] out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
      return VERSION + Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Could not encrypt the webhook secret", ex);
    }
  }

  public String decrypt(String stored, String merchantId) {
    SecretKey k = requireKey();
    try {
      if (stored == null || !stored.startsWith(VERSION)) {
        throw new GeneralSecurityException("unknown format");
      }
      byte[] data = Base64.getDecoder().decode(stored.substring(VERSION.length()));
      if (data.length <= IV_BYTES) {
        throw new GeneralSecurityException("too short");
      }
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
      cipher.updateAAD(merchantId.getBytes(StandardCharsets.UTF_8));
      byte[] plain = cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      throw new WebhookSecretException("The webhook signing secret cannot be decrypted", ex);
    }
  }

  private SecretKey requireKey() {
    if (key == null) {
      throw new WebhooksUnavailableException("Webhooks are not configured on this server.");
    }
    return key;
  }

  private static SecretKey parseKey(String base64, boolean required) {
    if (base64 == null || base64.isBlank()) {
      if (required) {
        throw new IllegalStateException(
            "WEBHOOK_SECRET_KEY must be set (base64 of 32 random bytes) when webhooks are enabled");
      }
      return null;
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("WEBHOOK_SECRET_KEY is not valid base64");
    }
    if (bytes.length != KEY_BYTES) {
      throw new IllegalStateException("WEBHOOK_SECRET_KEY must decode to exactly 32 bytes");
    }
    return new SecretKeySpec(bytes, "AES");
  }
}
