package com.shelfj.iam.mfa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Recovery codes (20.12): ten single-use codes shown once when a second factor is set up, for the
 * day the phone is lost. Sixty random bits each, written in an alphabet with no look-alikes; only
 * their hashes are kept — a fast hash is enough for a secret that is random rather than chosen.
 */
public final class RecoveryCodes {

  public static final int COUNT = 10;
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I, O, 0, 1
  private static final int LENGTH = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  private RecoveryCodes() {}

  /** Ten new codes, as shown to their owner: {@code XXXX-XXXX-XXXX}. */
  public static List<String> generate() {
    List<String> codes = new ArrayList<>(COUNT);
    for (int i = 0; i < COUNT; i++) {
      StringBuilder code = new StringBuilder();
      for (int c = 0; c < LENGTH; c++) {
        if (c > 0 && c % 4 == 0) code.append('-');
        code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
      }
      codes.add(code.toString());
    }
    return codes;
  }

  /** The stored form of a code, however its owner typed it: case, spaces and dashes forgiven. */
  public static String hash(String code) {
    String clean =
        code == null ? "" : code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(clean.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Whether the text has the shape of a recovery code at all. */
  public static boolean looksLikeOne(String code) {
    if (code == null) return false;
    String clean = code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    return clean.length() == LENGTH && clean.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0);
  }
}
