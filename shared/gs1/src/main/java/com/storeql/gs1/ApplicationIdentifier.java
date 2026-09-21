package com.storeql.gs1;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The application identifiers a retail scan carries, and how long each one's data is.
 *
 * <p>This table is the whole difficulty of reading an element string. An AI's data is either a
 * fixed length, in which case the next AI begins immediately after it, or variable, in which case
 * it runs to a separator or to the end. Get one length wrong and everything after it is read as
 * something else — a batch number becomes an expiry date, silently, and the scan still "works". So
 * the lengths are data here, each with the standard's own definition beside it, rather than
 * arithmetic spread through a parser.
 *
 * <p>Only the AIs a shop actually meets are listed. An unknown AI is <b>refused</b>, not skipped:
 * skipping one means guessing where its data ended, and a wrong guess mis-reads the rest of the
 * code. Adding one here is how the platform learns to read more, deliberately.
 */
public final class ApplicationIdentifier {

  private ApplicationIdentifier() {}

  /** An AI's data length: fixed at {@code length}, or variable up to {@code length}. */
  record Spec(String ai, int length, boolean fixed, String means) {}

  private static final Map<String, Spec> TABLE = new LinkedHashMap<>();

  private static void fixed(String ai, int length, String means) {
    TABLE.put(ai, new Spec(ai, length, true, means));
  }

  private static void variable(String ai, int max, String means) {
    TABLE.put(ai, new Spec(ai, max, false, means));
  }

  static {
    // Identification.
    fixed("00", 18, "SSCC — the pallet or carton itself, not what is in it");
    fixed("01", 14, "GTIN — which trade item this is");
    fixed("02", 14, "GTIN of the trade items contained in a logistic unit");

    // Dates. Six digits, YYMMDD, and DD may be 00 meaning "the end of the month".
    fixed("11", 6, "production date");
    fixed("12", 6, "due date");
    fixed("13", 6, "packaging date");
    fixed("15", 6, "best before");
    fixed("16", 6, "sell by");
    fixed("17", 6, "expiry — the date after which the item must not be sold");

    // Batch, serial and the rest a shop reads off a food label.
    variable("10", 20, "batch or lot number");
    variable("21", 20, "serial number");
    variable("240", 30, "additional item identification");
    variable("30", 8, "count of items in a variable-count trade item");
    variable("37", 8, "count of trade items in a logistic unit");
    variable("7003", 10, "expiry date and time");
    variable("710", 20, "national healthcare reimbursement number");

    // Measures. The last digit of the AI is the number of decimal places, so 3103 is kilograms to
    // three places: 3103/001250 is 1.250 kg. They are registered here without that digit and
    // matched
    // by prefix, because otherwise this table would carry sixty near-identical rows.
    for (int decimals = 0; decimals <= 5; decimals++) {
      fixed("310" + decimals, 6, "net weight, kilograms");
      fixed("311" + decimals, 6, "length, metres");
      fixed("315" + decimals, 6, "net volume, litres");
      fixed("316" + decimals, 6, "net volume, cubic metres");
      fixed("320" + decimals, 6, "net weight, pounds");
      fixed("330" + decimals, 6, "logistic weight, kilograms");
      fixed("350" + decimals, 6, "area, square metres");
      // Money. 392n is an amount in the invoicing party's own currency; 393n names the currency in
      // three digits of ISO 4217 first, which is why it is variable and three digits longer.
      variable("392" + decimals, 15, "amount payable, single monetary area");
      variable("393" + decimals, 18, "amount payable with ISO 4217 currency");
      variable("390" + decimals, 15, "amount payable, single monetary area (logistic)");
      variable("391" + decimals, 18, "amount payable with ISO 4217 currency (logistic)");
    }
  }

  /** What the standard says about an AI, or null when this platform does not read it. */
  static Spec of(String ai) {
    return TABLE.get(ai);
  }

  /**
   * The AI beginning at {@code from}, or null when nothing there is one.
   *
   * <p>AIs are two to four digits and are <b>not</b> a prefix-free code — {@code 31} begins nothing
   * but {@code 310} and {@code 3103} are both in the table. Longest match first is therefore the
   * only correct order, and shortest-first reads {@code 3103} as {@code 310} and then loses a digit
   * of the weight.
   */
  static Spec beginningAt(String data, int from) {
    for (int length = 4; length >= 2; length--) {
      if (from + length > data.length()) continue;
      Spec spec = TABLE.get(data.substring(from, from + length));
      if (spec != null) return spec;
    }
    return null;
  }

  /**
   * The number of decimal places a measurement AI carries in its last digit, or 0.
   *
   * <p>{@code 3103} is kilograms to three places. The digit is part of the AI, not of the data,
   * which is why a parser that treats the AI as three characters reads the weight a factor of ten
   * out.
   */
  static int decimals(String ai) {
    if (ai.length() != 4) return 0;
    char last = ai.charAt(3);
    return last >= '0' && last <= '9' ? last - '0' : 0;
  }

  /** Whether this AI is one of the measurement or money AIs whose value has decimal places. */
  static boolean isDecimalMeasure(String ai) {
    if (ai.length() != 4) return false;
    String family = ai.substring(0, 3);
    return switch (family) {
      case "310", "311", "315", "316", "320", "330", "350", "390", "391", "392", "393" -> true;
      default -> false;
    };
  }
}
