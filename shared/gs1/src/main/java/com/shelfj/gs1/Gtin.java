package com.shelfj.gs1;

/**
 * A GTIN, and the one form everything else compares against.
 *
 * <p>The same trade item is written four ways — GTIN-8 on a small pack, GTIN-12 (UPC-A) in North
 * America, GTIN-13 (EAN-13) in Europe, GTIN-14 on a case — and a 2D code always carries the
 * 14-digit form. So a shop that typed an EAN-13 into the catalogue and a till that scans the
 * GTIN-14 of the same item are naming one product, and a string comparison says they are not. GS1's
 * own rule is that the shorter forms are the 14-digit form with leading zeros, which is what {@link
 * #normalise(String)} produces and what a lookup must key on.
 *
 * <p>The check digit is verified, never repaired. A misread digit that still satisfies the check is
 * a different item that exists; one that fails the check is not an item at all, and guessing which
 * digit was wrong would sell the wrong thing at the wrong price.
 */
public final class Gtin {

  private Gtin() {}

  /** The lengths GS1 defines. Anything else is not a GTIN, whatever else it may be. */
  private static final int[] LENGTHS = {8, 12, 13, 14};

  /**
   * The 14-digit form of a GTIN, or null when the argument is not one.
   *
   * <p>Null rather than an exception: a till scans whatever the customer's packet carries, and a
   * code that is not a GTIN is an ordinary event — a loyalty card, a coupon, a staff badge — not a
   * fault. The caller decides what to do with something it cannot identify.
   */
  public static String normalise(String code) {
    if (code == null) return null;
    String digits = code.strip();
    if (!isDigits(digits) || !hasGtinLength(digits) || !checkDigitHolds(digits)) return null;
    return "0".repeat(14 - digits.length()) + digits;
  }

  /** Whether a string is a GTIN in any of its four lengths, check digit and all. */
  public static boolean valid(String code) {
    return normalise(code) != null;
  }

  /**
   * The check digit a body of digits ought to end with, by GS1's modulo-10 rule.
   *
   * <p>Weights alternate 3 and 1 from the digit <em>before</em> the check digit backwards, which is
   * why this walks from the right: a fixed left-to-right weighting is right for one GTIN length and
   * wrong for the other three, and that mistake reads an EAN-13 correctly while refusing every
   * GTIN-14.
   *
   * @param withoutCheckDigit the digits up to but not including the check digit
   */
  public static int checkDigit(String withoutCheckDigit) {
    int sum = 0;
    int weight = 3;
    for (int i = withoutCheckDigit.length() - 1; i >= 0; i--) {
      sum += (withoutCheckDigit.charAt(i) - '0') * weight;
      weight = weight == 3 ? 1 : 3;
    }
    return (10 - sum % 10) % 10;
  }

  private static boolean checkDigitHolds(String digits) {
    String body = digits.substring(0, digits.length() - 1);
    int last = digits.charAt(digits.length() - 1) - '0';
    return checkDigit(body) == last;
  }

  private static boolean hasGtinLength(String digits) {
    for (int length : LENGTHS) {
      if (digits.length() == length) return true;
    }
    return false;
  }

  static boolean isDigits(String s) {
    if (s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
    }
    return true;
  }
}
