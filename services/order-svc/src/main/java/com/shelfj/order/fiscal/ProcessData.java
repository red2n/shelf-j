package com.shelfj.order.fiscal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What a German security module signs about a sale, in the shapes the law fixes (18.5).
 *
 * <p>DSFinV-K Anlage I defines the process data for a receipt ({@code Kassenbeleg-V1}) as {@code
 * Beleg^<gross at 19%>_<7%>_<10.7%>_<5.5%>_<0%>^<amount>:<Bar|Unbar>[_…]}, and KassenSichV §6 fixes
 * what the receipt prints, which the same Anlage renders as one QR code. Both are built here, from
 * figures alone, so a provider and a test see the same strings.
 */
public final class ProcessData {

  private ProcessData() {}

  /** The five rate positions of Kassenbeleg-V1, in order, as percentages. */
  public static final List<BigDecimal> DE_RATE_POSITIONS =
      List.of(
          new BigDecimal("19.00"),
          new BigDecimal("7.00"),
          new BigDecimal("10.70"),
          new BigDecimal("5.50"),
          new BigDecimal("0.00"));

  /**
   * DSFinV-K's UST_SCHLUESSEL for each rate position (1 = 19%, 2 = 7%, 3 = 10.7%, 4 = 5.5%, 5 =
   * 0%).
   */
  public static final Map<Integer, String> DE_VAT_KEY_NAMES =
      Map.of(
          1,
          "Allgemeiner Steuersatz",
          2,
          "Ermäßigter Steuersatz",
          3,
          "Durchschnittsatz (§ 24 (1) Nr. 3 UStG)",
          4,
          "Durchschnittsatz (§ 24 (1) Nr. 1 UStG)",
          5,
          "Umsatzsteuerfrei");

  /**
   * Which of the five positions a rate belongs to: the nearest, so a 19.0 and a 19 agree, and a
   * rate the German scheme does not know is not silently dropped from the total.
   *
   * @param ratePercent the rate as a percentage
   * @return 1..5
   */
  public static int position(BigDecimal ratePercent) {
    int best = 5;
    BigDecimal bestDistance = null;
    for (int i = 0; i < DE_RATE_POSITIONS.size(); i++) {
      BigDecimal d = DE_RATE_POSITIONS.get(i).subtract(ratePercent).abs();
      if (bestDistance == null || d.compareTo(bestDistance) < 0) {
        bestDistance = d;
        best = i + 1;
      }
    }
    return best;
  }

  /** The five gross figures, one per position, in position order. */
  public static BigDecimal[] grossByPosition(List<SaleFigures.RateAmount> grossByRate) {
    BigDecimal[] out = new BigDecimal[5];
    java.util.Arrays.fill(out, BigDecimal.ZERO);
    for (var r : grossByRate) {
      int p = position(r.ratePercent()) - 1;
      out[p] = out[p].add(r.gross());
    }
    return out;
  }

  /**
   * The process data of one receipt: {@code Beleg^19.00_7.00_0.00_0.00_0.00^12.00:Bar}.
   *
   * @param sale the figures
   * @return the string the device signs and the receipt encodes
   */
  public static String kassenbeleg(SaleFigures sale) {
    StringBuilder sb = new StringBuilder("Beleg^");
    BigDecimal[] gross = grossByPosition(sale.grossByRate());
    for (int i = 0; i < gross.length; i++) {
      if (i > 0) {
        sb.append('_');
      }
      sb.append(money(gross[i]));
    }
    sb.append('^');
    if (sale.tenders().isEmpty()) {
      // A sale with no tender in the ledger is still a sale that was paid for; without the split
      // the whole amount is reported as non-cash, which is the safe direction for a cash audit.
      sb.append(money(sale.gross())).append(":Unbar");
    } else {
      boolean first = true;
      for (var t : sale.tenders()) {
        if (!first) {
          sb.append('_');
        }
        first = false;
        sb.append(money(t.amount())).append(':').append(t.isCash() ? "Bar" : "Unbar");
      }
    }
    return sb.toString();
  }

  /**
   * The receipt's QR code payload (DSFinV-K Anlage I, KassenSichV §6): {@code
   * V0;<clientId>;<processType>;<processData>;<transactionNumber>;<signatureCounter>;<start>;<end>;
   * <algorithm>;<timeFormat>;<signature>;<publicKey>}.
   */
  public static String qr(
      String clientId,
      String processType,
      String processData,
      long transactionNumber,
      long signatureCounter,
      Instant startedAt,
      Instant finishedAt,
      String algorithm,
      String timeFormat,
      String signature,
      String publicKey) {
    return String.join(
        ";",
        "V0",
        clientId,
        processType,
        processData,
        Long.toString(transactionNumber),
        Long.toString(signatureCounter),
        startedAt.toString(),
        finishedAt.toString(),
        algorithm,
        timeFormat,
        signature,
        publicKey);
  }

  /** A cloud device's name for a rate position: what a fiskaly-shaped API calls it. */
  public static String cloudVatRateName(int position) {
    return switch (position) {
      case 1 -> "NORMAL";
      case 2 -> "REDUCED_1";
      case 3 -> "SPECIAL_RATE_1";
      case 4 -> "SPECIAL_RATE_2";
      default -> "NULL";
    };
  }

  /** Two decimals, a dot, no grouping — what the process data and the QR carry. */
  public static String money(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
