package com.storeql.inventory.domain;

import com.storeql.inventory.domain.Domain.GrossMarginRow;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Gross margin and GMROI from the figures the ledger replay and the sale revenue give (19.7): the
 * arithmetic alone, so every rule — the nulls especially — is tested without a database.
 */
public final class GrossMargin {

  private GrossMargin() {}

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final BigDecimal YEAR = BigDecimal.valueOf(365);

  /**
   * @param revenue net revenue in the window, returns already netted; null means none
   * @param saleCogs cost of the stock sold, from the batches the sales drew down
   * @param returnCost cost taken back by returns, negative
   * @param averageValue the holding's average value at cost over the window
   * @param windowDays the window's length, at least one
   */
  public static GrossMarginRow row(
      String groupKey,
      BigDecimal revenue,
      BigDecimal saleCogs,
      BigDecimal returnCost,
      BigDecimal averageValue,
      BigDecimal uncostedSaleQty,
      BigDecimal unpricedSaleQty,
      int windowDays) {
    // Money keeps the scale it arrived in: revenue the currency's, from order-svc; cost the cost
    // price's, from this service. Only the ratios are rounded here.
    BigDecimal rev = nz(revenue);
    BigDecimal cogs = nz(saleCogs).add(nz(returnCost));
    BigDecimal margin = rev.subtract(cogs);
    BigDecimal percent =
        rev.signum() == 0 ? null : margin.multiply(HUNDRED).divide(rev, 1, RoundingMode.HALF_UP);
    BigDecimal avg = nz(averageValue);
    BigDecimal gmroi = avg.signum() <= 0 ? null : margin.divide(avg, 2, RoundingMode.HALF_UP);
    BigDecimal annual =
        gmroi == null
            ? null
            : margin
                .multiply(YEAR)
                .divide(
                    avg.multiply(BigDecimal.valueOf(Math.max(1, windowDays))),
                    2,
                    RoundingMode.HALF_UP);
    return new GrossMarginRow(
        groupKey,
        rev,
        cogs,
        margin,
        percent,
        avg,
        gmroi,
        annual,
        nz(uncostedSaleQty),
        nz(unpricedSaleQty));
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
