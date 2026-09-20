package com.storeql.gs1;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a scanned code said.
 *
 * <p>A reading, never an interpretation: this says the code carried GTIN {@code 09506000134352},
 * batch {@code ABC123} and an expiry of 31 December 2026. It does not say whether that item is on
 * sale, whether the batch is one this shop received, or whether the expiry means refuse the sale —
 * product-svc, inventory-svc and the till decide those, and each has a different answer.
 *
 * @param raw exactly what the scanner sent, kept so a refusal can quote it and a scan can be
 *     replayed
 * @param format how it was encoded
 * @param gtin the 14-digit GTIN, or null when the code carried none — a pallet label (SSCC only)
 *     and a coupon both legitimately carry none
 * @param elements every AI the code carried, in the order it carried them, as unconverted strings
 */
public record Gs1Scan(String raw, Format format, String gtin, Map<String, String> elements) {

  /** How a code was encoded. The reading is the same; only how it was written differs. */
  public enum Format {
    /** Digits alone: an EAN-13 or UPC from a linear barcode. No AIs, so no batch and no expiry. */
    PLAIN_GTIN,
    /** AI element strings, as GS1 DataMatrix, GS1-128 and GS1 QR carry them. */
    ELEMENT_STRING,
    /** A GS1 Digital Link URI, such as {@code https://id.gs1.org/01/09506000134352}. */
    DIGITAL_LINK
  }

  public Gs1Scan {
    elements =
        elements == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(elements));
  }

  /** AI 10 — the batch or lot, which is what a recall is issued against. */
  public Optional<String> batch() {
    return text("10");
  }

  /** AI 21 — the serial number, so a GTIN and serial together name one physical item. */
  public Optional<String> serial() {
    return text("21");
  }

  /** AI 00 — the SSCC of a logistic unit, present on a pallet label and nowhere else. */
  public Optional<String> sscc() {
    return text("00");
  }

  /** AI 17 — the date after which the item must not be sold. */
  public Optional<LocalDate> expiry() {
    return date("17");
  }

  /** AI 15 — best before, which is about quality and not about legality. */
  public Optional<LocalDate> bestBefore() {
    return date("15");
  }

  /** AI 11 — when it was made. */
  public Optional<LocalDate> productionDate() {
    return date("11");
  }

  /**
   * The net weight in kilograms (AI 310n), already scaled by the decimal places the AI declares.
   *
   * <p>Pounds (320n) are deliberately <b>not</b> folded in here. A conversion belongs where
   * somebody decided which unit the shop sells in, and quietly turning 2 lb into 0.907 kg inside a
   * parser would put a rounding nobody chose into a price.
   */
  public Optional<BigDecimal> netWeightKg() {
    return decimal("310");
  }

  /** The net weight in pounds (AI 320n), scaled, for a shop that sells in them. */
  public Optional<BigDecimal> netWeightLb() {
    return decimal("320");
  }

  /**
   * The amount payable the code carries (AI 392n or 393n), scaled by the AI's decimal places.
   *
   * <p>392n is an amount on its own. 393n puts three digits of ISO 4217 <em>in front of</em> it, so
   * the amount is what follows them — scaling the whole field instead reads &pound;12.50 as
   * &pound;8,261.25 for a sterling label, because the 826 is still on the front of it.
   */
  public Optional<BigDecimal> amountPayable() {
    Optional<BigDecimal> own = decimal("392", 0);
    return own.isPresent() ? own : decimal("393", 3);
  }

  /**
   * The ISO 4217 numeric currency AI 393n names, when it is the AI that carried the amount.
   *
   * <p>Numeric and not alphabetic, because that is what the standard puts in the code; the caller
   * maps it, since a map from 826 to GBP is a decision about which currencies the platform knows.
   */
  public Optional<String> currencyNumeric() {
    return byFamily("393").map(e -> e.getValue().substring(0, 3));
  }

  /** Whether this reading names a trade item at all. */
  public boolean identifiesItem() {
    return gtin != null;
  }

  // ── reading one element ─────────────────────────────────────────────────────

  private Optional<String> text(String ai) {
    return Optional.ofNullable(elements.get(ai));
  }

  /**
   * A GS1 six-digit date, {@code YYMMDD}.
   *
   * <p>Two rules that are easy to miss and both matter. A day of {@code 00} means "the end of that
   * month", so 261200 is 31 December 2026 and not an invalid date to be thrown away — a packet with
   * that on it is perfectly legal and a till that refuses it stops a sale for no reason. And the
   * century comes from the standard's own 50-year window, not from "20" + YY, so a shelf-stable tin
   * marked 49 is 2049 while a two-digit year of 51 is 1951.
   */
  private Optional<LocalDate> date(String ai) {
    return text(ai).flatMap(Gs1Dates::parse);
  }

  private Optional<BigDecimal> decimal(String family) {
    return decimal(family, 0);
  }

  /**
   * The value of the first AI in {@code family}, scaled, ignoring {@code skip} leading characters.
   *
   * @param skip characters of the field that are not part of the number — three, for the currency
   *     AI 393n puts in front of its amount, and none for everything else
   */
  private Optional<BigDecimal> decimal(String family, int skip) {
    return byFamily(family)
        .filter(e -> e.getValue().length() > skip)
        .map(
            e ->
                new BigDecimal(e.getValue().substring(skip))
                    .movePointLeft(ApplicationIdentifier.decimals(e.getKey())));
  }

  /** The first element whose AI begins with {@code family}, e.g. any of 3100..3105. */
  private Optional<Map.Entry<String, String>> byFamily(String family) {
    for (Map.Entry<String, String> e : elements.entrySet()) {
      if (e.getKey().length() == 4 && e.getKey().startsWith(family)) return Optional.of(e);
    }
    return Optional.empty();
  }
}
