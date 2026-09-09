package com.shelfj.purchase.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/**
 * Currency-aware rounding for purchase-order money (golden rule #13: {@code BigDecimal} throughout,
 * never floating point).
 *
 * <p>The reason this exists rather than a bare {@code setScale(2)}: Shelf-J is multi-currency, and
 * two decimal places is a property of sterling and the dollar, not of money. ISO 4217 gives JPY and
 * KRW <b>zero</b> minor units, so a Japanese supplier's order line of ¥1,234 rounded to two places
 * reads ¥1,234.00 — a figure that cannot be invoiced, paid or reconciled, and which quietly implies
 * a precision the currency does not have. {@link Currency#getDefaultFractionDigits()} is the JDK's
 * own copy of that table, so the scale comes from the currency rather than from an assumption.
 *
 * <p>Deliberately local to purchase-svc rather than promoted to {@code common-web}. Nothing else
 * needs it today, and a shared utility with one caller is surface for nobody — the same reasoning
 * SJ-D11 applied to {@code /customers/{id}}. When a second service needs currency-aware rounding,
 * move it then rather than leaving two copies to diverge, which is what SJ-D9 was.
 */
public final class Money {

  private Money() {}

  /**
   * Used when a currency code is not in the JDK's ISO 4217 table. Two places is the commonest minor
   * unit and the least surprising fallback, but reaching it means the code was never validated —
   * {@link #requireIso4217} is how client input is kept out of here.
   */
  private static final int FALLBACK_SCALE = 2;

  /**
   * The number of minor units the currency actually has: 2 for GBP, USD, CNY and INR; 0 for JPY.
   *
   * @param currencyCode ISO 4217 alpha-3 code, case-insensitive
   * @return the currency's minor-unit count, or 2 for an unrecognised code
   */
  public static int scaleOf(String currencyCode) {
    if (currencyCode == null) return FALLBACK_SCALE;
    try {
      int digits =
          Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT))
              .getDefaultFractionDigits();
      // -1 marks a pseudo-currency (XXX "no currency", XAU gold). Not spendable, but not worth
      // failing a purchase order over either — round it like ordinary money.
      return digits < 0 ? FALLBACK_SCALE : digits;
    } catch (IllegalArgumentException e) {
      return FALLBACK_SCALE;
    }
  }

  /**
   * Rounds to the currency's own minor units, HALF_UP — the commercial convention, and the one
   * every UK and EU VAT authority specifies for invoice rounding.
   *
   * @param amount the unrounded amount
   * @param currencyCode ISO 4217 alpha-3 code
   * @return {@code amount} at the currency's scale; null in, null out
   */
  public static BigDecimal round(BigDecimal amount, String currencyCode) {
    return amount == null ? null : amount.setScale(scaleOf(currencyCode), RoundingMode.HALF_UP);
  }

  /**
   * Whether the JDK recognises this as an ISO 4217 currency.
   *
   * @param currencyCode the code to test
   * @return {@code true} if {@link Currency#getInstance(String)} accepts it
   */
  public static boolean isIso4217(String currencyCode) {
    if (currencyCode == null || currencyCode.trim().length() != 3) return false;
    try {
      Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT));
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Normalises and validates a client-supplied currency code at the boundary (golden rule #15).
   *
   * @param currencyCode the code as the caller sent it
   * @return the upper-cased, trimmed code
   * @throws com.shelfj.web.ApiException 400 {@code PURCHASE_INVALID_CURRENCY} if it is not ISO 4217
   */
  public static String requireIso4217(String currencyCode) {
    if (!isIso4217(currencyCode)) {
      throw com.shelfj.web.ApiException.badRequest(
          "PURCHASE_INVALID_CURRENCY",
          "currency must be an ISO 4217 alpha-3 code (e.g. GBP, USD, JPY, INR, CNY): "
              + currencyCode);
    }
    return currencyCode.trim().toUpperCase(Locale.ROOT);
  }
}
