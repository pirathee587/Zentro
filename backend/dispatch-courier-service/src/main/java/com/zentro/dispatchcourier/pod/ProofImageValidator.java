package com.zentro.dispatchcourier.pod;

import com.zentro.dispatchcourier.exception.ProofException;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

/** Validates bytes, not a filename or browser supplied content type. */
public final class ProofImageValidator {
  private ProofImageValidator() {}
  public static final long MAX_BYTES = 5L * 1024 * 1024;
  public static String validate(MultipartFile file) {
    if (file == null || file.isEmpty()) throw invalid("photo", "A delivery photo is required.");
    if (file.getSize() > MAX_BYTES) throw invalid("photo", "The image must be 5 MB or smaller.");
    try {
      byte[] h = file.getInputStream().readNBytes(16);
      if (h.length >= 3 && h[0] == (byte) 0xff && h[1] == (byte) 0xd8 && h[2] == (byte) 0xff) return "image/jpeg";
      if (h.length >= 8 && h[0] == (byte) 0x89 && h[1] == 0x50 && h[2] == 0x4e && h[3] == 0x47 && h[4] == 0x0d && h[5] == 0x0a && h[6] == 0x1a && h[7] == 0x0a) return "image/png";
      if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "image/webp";
    } catch (java.io.IOException ignored) { throw invalid("photo", "The image could not be read."); }
    throw invalid("photo", "Use a JPEG, PNG, or WebP image.");
  }
  private static ProofException invalid(String field, String message) {
    return new ProofException(400, "INVALID_PROOF_IMAGE", "Check the delivery photo.", Map.of(field, message));
  }
}
