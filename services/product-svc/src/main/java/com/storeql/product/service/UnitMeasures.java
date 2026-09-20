package com.storeql.product.service;

import com.storeql.product.domain.Domain.UnitMeasure;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * How much of what a variant's price buys, in the unit its unit price is shown per (03.13).
 *
 * <p>The Price Marking Order and Directive 98/6/EC art.3 ask for a price per kilogram, litre,
 * metre, square metre, or per item for goods sold by number. A variant says how it is sold (each,
 * or by weight, volume or length) and the net content its price is for; this turns that into the
 * standard unit and the quantity of it one price buys. Nothing is guessed: a measure that is not
 * declared, not convertible, or not the kind of thing the item is sold by gives no measure, and the
 * price quote says so rather than showing a wrong unit price.
 */
public final class UnitMeasures {

  public static final String KG = "KG";
  public static final String LITRE = "L";
  public static final String METRE = "M";
  public static final String SQUARE_METRE = "SQM";
  public static final String ITEM = "EA";

  /** The unit a unit price is shown per, for each class of unit. */
  static final Map<String, String> STANDARD =
      Map.of("WEIGHT", KG, "VOLUME", LITRE, "LENGTH", METRE, "AREA", SQUARE_METRE, "EACH", ITEM);

  private static final String EACH = "EACH";
  private static final String WEIGHT = "WEIGHT";

  private UnitMeasures() {}

  /**
   * @param soldBy EACH, WEIGHT, VOLUME or LENGTH
   * @param netContent the quantity one price buys, in {@code netContentUom}; for an item sold by
   *     weight, volume or length, absent means one of that unit
   * @param netContentUom the unit code, such as G, KG, ML, M or EA
   * @param catchWeight every item weighs differently and is priced per kilogram
   * @param classOf the class of a unit code (WEIGHT, VOLUME, LENGTH, AREA, EACH, TIME)
   * @param factor how many of the second unit one of the first is
   * @return the measure, or null when none can be stated truthfully
   */
  public static UnitMeasure of(
      String soldBy,
      BigDecimal netContent,
      String netContentUom,
      boolean catchWeight,
      Function<String, Optional<String>> classOf,
      BiFunction<String, String, Optional<BigDecimal>> factor) {
    String sold = soldBy == null ? EACH : soldBy;
    if (netContent != null && netContent.signum() <= 0) return null;
    if (netContentUom == null) {
      // A catch-weight item with no unit named is priced per kilogram.
      return catchWeight && (EACH.equals(sold) || WEIGHT.equals(sold))
          ? new UnitMeasure(KG, BigDecimal.ONE)
          : null;
    }
    String kind = classOf.apply(netContentUom).orElse(null);
    String standard = kind == null ? null : STANDARD.get(kind);
    if (standard == null) return null;
    // Sold by weight but measured in litres says two different things; neither is shown.
    if (!EACH.equals(sold) && !sold.equals(kind)) return null;
    // Sold by the each, the content is what the pack holds and has to be stated; sold by a
    // measure, the price is for one of that unit unless a content says otherwise.
    BigDecimal amount = netContent != null ? netContent : EACH.equals(sold) ? null : BigDecimal.ONE;
    if (amount == null) return null;
    BigDecimal f =
        netContentUom.equals(standard)
            ? BigDecimal.ONE
            : factor.apply(netContentUom, standard).orElse(null);
    if (f == null || f.signum() <= 0) return null;
    BigDecimal quantity = amount.multiply(f).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    return quantity.signum() > 0 ? new UnitMeasure(standard, quantity) : null;
  }
}
