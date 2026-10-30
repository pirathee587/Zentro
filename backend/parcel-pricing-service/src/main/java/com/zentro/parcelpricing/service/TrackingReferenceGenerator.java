package com.zentro.parcelpricing.service;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Creates public tracking references such as "ZT-7K3QX9M2PA".
 * The 10 characters are random Crockford Base32 (50 bits), so references cannot be guessed or enumerated.
 * Uniqueness is enforced by the database; callers retry on a collision.
 */
@Component
public class TrackingReferenceGenerator {
  public static final String PREFIX = "ZT-";
  public static final int RANDOM_LENGTH = 10;
  static final char[] CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

  private final RandomGenerator random;

  public TrackingReferenceGenerator() {
    this(new SecureRandom());
  }

  TrackingReferenceGenerator(RandomGenerator random) {
    this.random = random;
  }

  public String next() {
    StringBuilder ref = new StringBuilder(PREFIX.length() + RANDOM_LENGTH).append(PREFIX);
    for (int i = 0; i < RANDOM_LENGTH; i++) {
      ref.append(CROCKFORD_ALPHABET[random.nextInt(CROCKFORD_ALPHABET.length)]);
    }
    return ref.toString();
  }
}
