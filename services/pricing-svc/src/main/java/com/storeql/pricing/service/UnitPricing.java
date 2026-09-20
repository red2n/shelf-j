package com.storeql.pricing.service;

import com.storeql.pricing.domain.Domain.Measure;
import com.storeql.pricing.domain.Domain.UnitPrice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Map;

/**
 * A unit price from the price a shopper pays and the measure it buys (03.13). Pure: whether one is
 * required, and the measure, are decided elsewhere.
 */
public final class UnitPricing {

  /** The jurisdiction rule: the UK's Price Marking Order, the EU's Directive 98/6/EC art.3. */
  public static final String UNIT_PRICING = "UNIT_PRICING";

  /** How each standard unit reads beside a price. */
  static final Map<String, String> PER =
      Map.of("KG", "per kg", "L", "per litre", "M", "per metre", "SQM", "per m²", "EA", "each");

  private UnitPricing() {}

  /**
   * @param price what the shopper pays for one of the variant, VAT and any promotion included
   * @param measure what that price buys, or null when none was declared
   * @param currency the price's ISO 4217 code, which decides the rounding
   * @return the unit price, rounded half-up to the currency's minor unit; null without a measure
   */
  public static UnitPrice of(BigDecimal price, Measure measure, String currency) {
    if (price == null || measure == null || measure.quantity() == null) return null;
    if (measure.quantity().signum() <= 0 || !PER.containsKey(measure.unit())) return null;
    BigDecimal amount = price.divide(measure.quantity(), digits(currency), RoundingMode.HALF_UP);
    return new UnitPrice(amount, measure.unit(), measure.quantity(), PER.get(measure.unit()));
  }

  static int digits(String currency) {
    try {
      int d = Currency.getInstance(currency).getDefaultFractionDigits();
      return d < 0 ? 2 : d;
    } catch (RuntimeException e) {
      return 2;
    }
  }
}
