package com.zentro.notificationintegration.webhook.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs a delivery: {@code X-SwiftDrop-Signature: sha256=<hex HMAC-SHA256>} over {@code "<timestamp>.<raw body>"},
 * keyed with the merchant's secret (the full "whsec_..." string, UTF-8). The timestamp is unix seconds and is sent
 * in {@code X-SwiftDrop-Timestamp}, so receivers can reject old or replayed requests.
 */
@Component
public class WebhookSigner {
  public static final String SCHEME = "sha256=";
  private static final String ALGORITHM = "HmacSHA256";

  public String sign(String secret, long timestampSeconds, String body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      byte[] digest = mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
      return SCHEME + HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("HMAC-SHA256 is not available", ex);
    }
  }
}
