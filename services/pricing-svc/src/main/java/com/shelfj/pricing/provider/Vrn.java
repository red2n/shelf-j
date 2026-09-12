package com.shelfj.pricing.provider;

/**
 * A UK VAT registration number: nine digits whose last two are a check on the first seven — HMRC's
 * modulus-97 scheme, and since 2010 the 9755 variant beside it. A number that fails both is a typo,
 * and a typo filed with HMRC is a return filed for someone else.
 */
public final class Vrn {

  private Vrn() {}

  /**
   * @param vrn the candidate, with or without a {@code GB} prefix and spaces
   * @return the nine digits when the number is well-formed, or null
   */
  public static String normalise(String vrn) {
    if (vrn == null) {
      return null;
    }
    String digits = vrn.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
    if (digits.startsWith("GB")) {
      digits = digits.substring(2);
    }
    if (!digits.matches("[0-9]{9}")) {
      return null;
    }
    return isValid(digits) ? digits : null;
  }

  static boolean isValid(String nine) {
    int[] weights = {8, 7, 6, 5, 4, 3, 2};
    int sum = 0;
    for (int i = 0; i < 7; i++) {
      sum += (nine.charAt(i) - '0') * weights[i];
    }
    int check = Integer.parseInt(nine.substring(7));
    // Mod 97: subtract 97 until negative or zero; the check is the absolute value.
    int r = sum;
    while (r > 0) {
      r -= 97;
    }
    if (Math.abs(r) == check) {
      return true;
    }
    // Mod 9755: the same with 55 added first.
    r = sum + 55;
    while (r > 0) {
      r -= 97;
    }
    return Math.abs(r) == check;
  }
}
