package com.storeql.payment.settlement;

import com.storeql.payment.domain.Settlements;
import com.storeql.payment.domain.Settlements.ParsedFile;
import com.storeql.payment.domain.Settlements.ParsedLine;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adyen's settlement details report: one row per journal entry in a settlement batch, with debits
 * and credits in separate columns — gross in the currency the shopper paid, net in the currency
 * paid out — and what Adyen and the schemes kept spread over commission, markup, scheme fees and
 * interchange. Here gross and net are each credit less debit and the fee is the difference, which
 * is only true in one currency: a row converted between two is refused, because the difference
 * would then be an exchange rate and not a fee. {@code MerchantPayout} is the payment into the bank
 * and says what the batch adds up to. For a refund or a chargeback {@code Psp Reference} is the
 * payment's and {@code Modification Reference} its own.
 */
@ApplicationScoped
public class AdyenSettlementParser implements SettlementFileParser {

  public static final String FORMAT = "ADYEN";

  /** Not a line: the payment into the bank, which says what the lines add up to. */
  private static final String PAYOUT = "PAYOUT";

  @Override
  public String format() {
    return FORMAT;
  }

  @Override
  public ParsedFile parse(String content) {
    Csv.Table table = Tables.read(content, "type", "psp reference", "net currency");
    List<ParsedLine> lines = new ArrayList<>(table.size());
    String batch = null;
    String currency = null;
    BigDecimal declared = null;
    Instant paidAt = null;
    for (int row = 0; row < table.size(); row++) {
      String kind = table.get(row, "type");
      if (kind == null) {
        throw new SettlementFileException(Fields.INVALID, Fields.at(row) + "Type is missing");
      }
      batch = Fields.same(batch, table.get(row, "batch number"), row, "settlement batch");
      String netCurrency = Fields.currency(table.get(row, "net currency"), row);
      String grossCurrency = Fields.currency(table.get(row, "gross currency"), row);
      currency = Fields.same(currency, netCurrency, row, "currency");
      BigDecimal net =
          Fields.amountOrZero(table.get(row, "net credit (nc)"), row, "Net Credit (NC)")
              .subtract(
                  Fields.amountOrZero(table.get(row, "net debit (nc)"), row, "Net Debit (NC)"));
      Instant at =
          Fields.time(
              table.get(row, "creation date"), table.get(row, "timezone"), row, "Creation Date");
      String type = typeOf(kind);
      if (PAYOUT.equals(type)) {
        declared = net.negate();
        paidAt = at;
        continue;
      }
      BigDecimal gross =
          Fields.amountOrZero(table.get(row, "gross credit (gc)"), row, "Gross Credit (GC)")
              .subtract(
                  Fields.amountOrZero(table.get(row, "gross debit (gc)"), row, "Gross Debit (GC)"));
      if (grossCurrency != null && netCurrency != null && !grossCurrency.equals(netCurrency)) {
        throw new SettlementFileException(
            "SETTLEMENT_CURRENCY_MIXED",
            Fields.at(row)
                + "paid in "
                + grossCurrency
                + " and settled in "
                + netCurrency
                + ": a converted line cannot be reconciled to the payment");
      }
      boolean whole = Settlements.FEE.equals(type) || Settlements.ADJUSTMENT.equals(type);
      String psp = Fields.reference(table.get(row, "psp reference"), row, "Psp Reference");
      String own =
          Fields.reference(table.get(row, "modification reference"), row, "Modification Reference");
      String merchant =
          Fields.reference(table.get(row, "merchant reference"), row, "Merchant Reference");
      boolean sale = Settlements.SALE.equals(type);
      lines.add(
          Lines.of(
              type,
              sale || own == null ? (psp == null ? merchant : psp) : own,
              sale ? merchant : psp,
              whole ? net : gross,
              whole ? BigDecimal.ZERO.setScale(4) : gross.subtract(net),
              net,
              at,
              row));
    }
    LocalDate paidOn = paidAt == null ? null : paidAt.atOffset(ZoneOffset.UTC).toLocalDate();
    return new ParsedFile(lines, batch, paidOn, currency, declared);
  }

  /**
   * Adyen's journal types, in the kinds of line every layout comes down to, or the payout itself.
   */
  static String typeOf(String kind) {
    return switch (kind.toLowerCase(Locale.ROOT)) {
      case "settled", "settledbulk", "settledexternallywithinfo" -> Settlements.SALE;
      case "refunded", "refundedbulk", "refundedexternallywithinfo" -> Settlements.REFUND;
      case "chargeback", "secondchargeback", "chargebackexternallywithinfo" ->
          Settlements.CHARGEBACK;
      case "chargebackreversed", "chargebackreversedexternallywithinfo" ->
          Settlements.CHARGEBACK_REVERSAL;
      case "fee", "invoicededuction", "paymentcost" -> Settlements.FEE;
      case "merchantpayout" -> PAYOUT;
      default -> Settlements.ADJUSTMENT;
    };
  }
}
