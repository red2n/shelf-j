package com.shelfj.payment.settlement;

import com.shelfj.payment.domain.Settlements;
import com.shelfj.payment.domain.Settlements.ParsedFile;
import com.shelfj.payment.domain.Settlements.ParsedLine;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stripe's itemised payout reconciliation report: one row per balance transaction in a payout, in
 * major units, with {@code gross}, {@code fee} and {@code net} already signed from the business's
 * side. {@code reporting_category} says what a row is; a payment is known here by its payment
 * intent, a refund and a dispute by their own ids with the payment beside them. The payout's id,
 * date and currency are in every row, and a file that covers more than one payout is refused: a
 * batch is one payment into the bank.
 */
@ApplicationScoped
public class StripeSettlementParser implements SettlementFileParser {

  public static final String FORMAT = "STRIPE";

  @Override
  public String format() {
    return FORMAT;
  }

  @Override
  public ParsedFile parse(String content) {
    Csv.Table table = Tables.read(content, "reporting_category", "gross", "net");
    List<ParsedLine> lines = new ArrayList<>(table.size());
    String payout = null;
    String currency = null;
    LocalDate paidOn = null;
    BigDecimal declared = null;
    for (int row = 0; row < table.size(); row++) {
      String category = table.get(row, "reporting_category");
      if (category == null) {
        throw new SettlementFileException(
            Fields.INVALID, Fields.at(row) + "reporting_category is missing");
      }
      payout =
          Fields.same(payout, table.get(row, "automatic_payout_id", "payout_id"), row, "payout");
      currency =
          Fields.same(currency, Fields.currency(table.get(row, "currency"), row), row, "currency");
      if (paidOn == null) {
        paidOn =
            Fields.date(
                table.get(
                    row,
                    "automatic_payout_effective_at_utc",
                    "automatic_payout_effective_at",
                    "payout_expected_arrival_date"),
                row,
                "automatic_payout_effective_at");
      }
      BigDecimal gross = Fields.amountOrZero(table.get(row, "gross"), row, "gross");
      BigDecimal fee = Fields.amountOrZero(table.get(row, "fee"), row, "fee");
      BigDecimal net = Fields.amount(table.get(row, "net"), row, "net");
      if ("payout".equals(category.toLowerCase(Locale.ROOT))) {
        // The payout itself leaves the Stripe balance: what it says is the sum the rest adds up to.
        declared = (net == null ? gross : net).negate();
        continue;
      }
      String payment =
          Fields.reference(table.get(row, "payment_intent_id", "charge_id"), row, "payment");
      String source = Fields.reference(table.get(row, "source_id"), row, "source_id");
      String type = typeOf(category);
      boolean sale = Settlements.SALE.equals(type);
      lines.add(
          Lines.of(
              type,
              sale && payment != null ? payment : source,
              sale ? Fields.reference(table.get(row, "charge_id"), row, "charge_id") : payment,
              gross,
              fee,
              net,
              Fields.time(table.get(row, "created_utc", "created"), null, row, "created"),
              row));
    }
    return new ParsedFile(lines, payout, paidOn, currency, declared);
  }

  /** Stripe's reporting categories, in the six kinds of line every layout comes down to. */
  static String typeOf(String category) {
    return switch (category.toLowerCase(Locale.ROOT)) {
      case "charge", "payment" -> Settlements.SALE;
      case "refund", "payment_refund", "partial_capture_reversal" -> Settlements.REFUND;
      case "dispute" -> Settlements.CHARGEBACK;
      case "dispute_reversal" -> Settlements.CHARGEBACK_REVERSAL;
      case "fee", "network_cost", "tax", "stripe_fee" -> Settlements.FEE;
      default -> Settlements.ADJUSTMENT;
    };
  }
}
