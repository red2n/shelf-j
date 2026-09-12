package com.shelfj.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The reduced-price sticker's barcode (05.4): an EAN-13 in the in-store range, prefix {@code 21}, a
 * five-digit item code from the tenant's series, the reduced price in minor units to five digits,
 * and the EAN check digit — the same variable-measure shape a labelling scale prints, with a price
 * instead of a weight, so a till that reads scale labels reads these.
 *
 * <p>Pure functions; the item code comes from a counter row the repository moves under its lock.
 */
public final class MarkdownLabel {

  private MarkdownLabel() {}

  /** The in-store prefix reduced-price stickers carry. */
  public static final String PREFIX = "21";

  /** The most a five-digit minor-unit field can say: 999.99 in a two-decimal currency. */
  public static final BigDecimal MAX_PRICE = new BigDecimal("999.99");

  /**
   * The thirteen digits for an item code and a price.
   *
   * @param itemNumber the tenant's series number; taken modulo 100000 to five digits
   * @param price the reduced price, two decimals
   * @return the sticker's code
   * @throws IllegalArgumentException when the price does not fit the label
   */
  public static String encode(long itemNumber, BigDecimal price) {
    if (price.signum() < 0 || price.compareTo(MAX_PRICE) > 0) {
      throw new IllegalArgumentException("a sticker price must be between 0 and 999.99");
    }
    long minor = price.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    String twelve =
        PREFIX + String.format("%05d", itemNumber % 100000) + String.format("%05d", minor);
    return twelve + checkDigit(twelve);
  }

  /** The price a sticker's code carries, or null when the code is not a reduced-price sticker. */
  public static BigDecimal priceOf(String code) {
    if (!isLabel(code)) {
      return null;
    }
    return new BigDecimal(code.substring(7, 12)).movePointLeft(2);
  }

  /** Whether a scanned code is a reduced-price sticker: thirteen digits, the prefix, the check. */
  public static boolean isLabel(String code) {
    return code != null
        && code.length() == 13
        && code.chars().allMatch(Character::isDigit)
        && code.startsWith(PREFIX)
        && checkDigit(code.substring(0, 12)) == code.charAt(12);
  }

  /** EAN-13: weights 1 and 3 from the left over twelve digits, the complement to ten. */
  public static char checkDigit(String twelve) {
    int sum = 0;
    for (int i = 0; i < 12; i++) {
      int d = twelve.charAt(i) - '0';
      sum += (i % 2 == 0) ? d : d * 3;
    }
    return (char) ('0' + (10 - (sum % 10)) % 10);
  }
}
