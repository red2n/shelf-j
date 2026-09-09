package com.shelfj.purchase.domain;

import com.shelfj.purchase.domain.Domain.PurchaseOrderLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * What a purchase order commits the business to (SJ-D22).
 *
 * <p>{@code total_net}, {@code total_vat} and {@code total_gross} have existed on {@code
 * purchase_orders} since V1, are returned by the API, and are rendered on the procurement screen in
 * two places. Nothing ever wrote them. They were inserted as zero and no {@code UPDATE} touching
 * them existed anywhere in the service, so <b>every purchase order ever raised displayed a total of
 * 0.00</b> however many lines it carried — the SJ-D8 shape (a declared feature whose data could
 * never be populated), and the seventh time it has been found on this branch.
 *
 * <p>A pure function over lines, a currency and a rate table — no database, no clock, no tenant —
 * for the reason SJ-D20 gave: the arithmetic that goes wrong across a service boundary is the
 * arithmetic no test can reach. Every case below is a unit test that runs in microseconds.
 *
 * @param net the sum of the line values, before tax
 * @param vat the tax on those lines, per each line's own VAT code
 * @param gross net plus vat — what the supplier will invoice
 */
public record Totals(BigDecimal net, BigDecimal vat, BigDecimal gross) {

  /**
   * Values a purchase order with no lines yet.
   *
   * @param currency the order's currency, which fixes the scale
   * @return zero at that currency's own scale
   */
  public static Totals zero(String currency) {
    BigDecimal z = Money.round(BigDecimal.ZERO, currency);
    return new Totals(z, z, z);
  }

  /**
   * Totals one purchase order's lines.
   *
   * <p><b>Rounded per line, then summed</b> — the convention every supplier invoice follows, and
   * the one that lets a receiving clerk reconcile a Shelf-J total against a paper delivery note
   * line by line. Rounding only the grand total would be defensible arithmetic and indefensible
   * bookkeeping: it produces a figure that no addition of the printed lines reproduces.
   *
   * <p>Scale comes from the currency, not from a constant. On a JPY order every line and every
   * total lands on a whole yen, because the yen has no minor unit — {@code setScale(2)} would
   * invent a precision that cannot be invoiced or paid.
   *
   * <p><b>A VAT code with no configured rate contributes zero</b>, and that is a decision rather
   * than a fallback. VAT rates are tenant configuration that nothing seeds at onboarding, so an
   * unknown code most often means "this tenant has not set VAT up" — a fresh tenant, a business
   * below the registration threshold, a jurisdiction where the code means nothing. Zero is what all
   * of those mean. The alternative, refusing the line, would stop a new tenant raising a purchase
   * order at all.
   *
   * @param lines the order's lines; an empty list yields {@link #zero}
   * @param currency the order's ISO 4217 currency, which fixes the rounding scale
   * @param vatRates VAT code to rate as a fraction (0.20 for 20%), from pricing-svc; missing codes
   *     rate at zero
   * @return the three totals, each at the currency's own scale
   */
  public static Totals of(
      List<PurchaseOrderLine> lines, String currency, Map<String, BigDecimal> vatRates) {
    BigDecimal net = BigDecimal.ZERO;
    BigDecimal vat = BigDecimal.ZERO;
    for (PurchaseOrderLine line : lines) {
      if (line.qty() == null || line.unitPrice() == null) continue;
      BigDecimal lineNet = Money.round(line.qty().multiply(line.unitPrice()), currency);
      net = net.add(lineNet);
      BigDecimal rate = rateFor(line.vatCode(), vatRates);
      if (rate.signum() != 0) {
        vat = vat.add(Money.round(lineNet.multiply(rate), currency));
      }
    }
    BigDecimal roundedNet = Money.round(net, currency);
    BigDecimal roundedVat = Money.round(vat, currency);
    return new Totals(roundedNet, roundedVat, roundedNet.add(roundedVat));
  }

  private static BigDecimal rateFor(String vatCode, Map<String, BigDecimal> vatRates) {
    if (vatCode == null || vatRates == null) return BigDecimal.ZERO;
    BigDecimal rate = vatRates.get(vatCode.trim().toUpperCase(java.util.Locale.ROOT));
    return rate == null ? BigDecimal.ZERO : rate;
  }
}
