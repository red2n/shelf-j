package com.storeql.payment.settlement;

import com.storeql.payment.domain.Settlements;
import com.storeql.payment.domain.Settlements.ParsedLine;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * What every layout's line must come down to before it is believed: signed from the business's
 * side, net equal to gross less fee, and the sign its type says it has. A file is never corrected:
 * a refund written as money in is refused, not flipped, because the other reading — a sale
 * mislabelled — is as likely and means the opposite.
 */
final class Lines {

  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);

  private Lines() {}

  /**
   * @param net what the file says was paid for the line, or null to have it worked out
   * @throws SettlementFileException when the line does not add up or has the wrong sign
   */
  static ParsedLine of(
      String type,
      String reference,
      String originalReference,
      BigDecimal gross,
      BigDecimal fee,
      BigDecimal net,
      Instant occurredAt,
      int row) {
    BigDecimal g = gross == null ? ZERO : gross;
    BigDecimal f = fee == null ? ZERO : fee;
    if (Settlements.FEE.equals(type) && f.signum() == 0 && g.signum() != 0) {
      // A charge written as money out with no fee column: the same thing said another way.
      f = g.negate();
      g = ZERO;
    }
    BigDecimal n = net == null ? g.subtract(f) : net;
    if (n.compareTo(g.subtract(f)) != 0) {
      throw invalid(row, "net is not gross less fee");
    }
    switch (type) {
      case Settlements.SALE, Settlements.CHARGEBACK_REVERSAL -> {
        if (g.signum() <= 0)
          throw invalid(row, "a " + words(type) + " brings money in: its gross must be above zero");
      }
      case Settlements.REFUND, Settlements.CHARGEBACK -> {
        if (g.signum() >= 0)
          throw invalid(row, "a " + words(type) + " takes money out: its gross must be below zero");
      }
      case Settlements.FEE -> {
        if (g.signum() != 0 || f.signum() == 0)
          throw invalid(row, "a fee line carries a fee and no gross");
      }
      case Settlements.ADJUSTMENT -> {
        if (g.signum() == 0 && f.signum() == 0) throw invalid(row, "an adjustment of nothing");
      }
      default ->
          throw invalid(
              row,
              "type is not one of "
                  + String.join(", ", Settlements.TYPES.stream().sorted().toList()));
    }
    boolean aboutAPayment = !Settlements.FEE.equals(type) && !Settlements.ADJUSTMENT.equals(type);
    if (aboutAPayment && reference == null && originalReference == null) {
      throw invalid(row, "a " + words(type) + " needs the acquirer's reference");
    }
    return new ParsedLine(type, reference, originalReference, g, f, n, occurredAt);
  }

  private static String words(String type) {
    return type.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
  }

  private static SettlementFileException invalid(int row, String what) {
    return new SettlementFileException(Fields.INVALID, Fields.at(row) + what);
  }
}
