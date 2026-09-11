package com.shelfj.purchase.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ordered against received against invoiced — the control that stops a business paying for goods it
 * did not order, did not get, or was charged the wrong price for.
 *
 * <p>A pure function over three quantities and two prices. No database, no clock, no tenant, for
 * the reason SJ-D20 gave: the arithmetic that goes wrong across a service boundary is the
 * arithmetic no test can reach. Every case below is a unit test that runs in microseconds.
 *
 * <p><b>Quantity is matched against what was RECEIVED, not what was ordered.</b> You pay for what
 * turned up. An order for 100 that delivered 60 and invoiced 60 is correct and must not be flagged;
 * the same order invoicing 100 is the single most common supplier error there is.
 *
 * <p><b>Price is matched against the ORDER</b>, because the receipt has no price — {@code
 * goods_receipt_lines} records quantity only. That is the right shape: a warehouse counts, it does
 * not negotiate.
 *
 * <p><b>Flag, do not block.</b> Nothing here refuses an invoice. An invoice that arrived is a fact,
 * and declining to record it because it disagrees with the order destroys the evidence of the
 * disagreement — which is the thing someone needs in order to argue with the supplier.
 */
public final class ThreeWayMatch {

  private ThreeWayMatch() {}

  /** Invoiced more of a variant than has been received across the whole order. */
  public static final String INVOICED_ABOVE_RECEIVED = "INVOICED_ABOVE_RECEIVED";

  /** The variant is on the invoice but nothing of it has been received yet. */
  public static final String NOT_RECEIVED = "NOT_RECEIVED";

  /** The variant is on the invoice but was never on the purchase order. */
  public static final String NOT_ON_ORDER = "NOT_ON_ORDER";

  /** Charged more per unit than the order agreed. */
  public static final String PRICE_ABOVE_ORDER = "PRICE_ABOVE_ORDER";

  /** Charged less per unit than the order agreed — still a variance, still worth knowing. */
  public static final String PRICE_BELOW_ORDER = "PRICE_BELOW_ORDER";

  /**
   * What the three documents say about one variant, and where they disagree.
   *
   * @param variantId the product
   * @param qtyOrdered what the purchase order asked for
   * @param qtyReceived what has arrived across every receipt on that order
   * @param qtyInvoicedBefore what earlier invoices on this order already billed for this variant
   * @param qtyInvoicedNow what this invoice bills
   * @param orderedUnitPrice the price the order agreed; null when the variant was never ordered
   * @param invoicedUnitPrice the price this invoice charges
   * @param variances every disagreement found, empty when the line agrees
   */
  public record MatchLine(
      UUID variantId,
      BigDecimal qtyOrdered,
      BigDecimal qtyReceived,
      BigDecimal qtyInvoicedBefore,
      BigDecimal qtyInvoicedNow,
      BigDecimal orderedUnitPrice,
      BigDecimal invoicedUnitPrice,
      List<String> variances) {

    public MatchLine {
      variances = List.copyOf(variances);
    }

    /** Everything billed for this variant on this order, including this invoice. */
    public BigDecimal qtyInvoicedTotal() {
      return qtyInvoicedBefore.add(qtyInvoicedNow);
    }

    public boolean matched() {
      return variances.isEmpty();
    }
  }

  /**
   * How much disagreement is accepted before a line is flagged.
   *
   * <p>Both default to zero, meaning any variance at all is surfaced. That is deliberate and it is
   * safe precisely because flagging does not block: the conservative default shows a buyer
   * everything until someone decides what their business actually tolerates. Choosing a non-zero
   * band is a procurement policy, not something to invent in code — the same line partial receipt
   * drew about over-receipt tolerance.
   *
   * @param pricePercent accepted per-unit price difference, as a percentage of the ordered price
   * @param qtyPercent accepted quantity difference, as a percentage of the received quantity
   */
  public record Tolerance(BigDecimal pricePercent, BigDecimal qtyPercent) {

    public static final Tolerance EXACT = new Tolerance(BigDecimal.ZERO, BigDecimal.ZERO);

    public Tolerance {
      if (pricePercent == null || qtyPercent == null) {
        throw new IllegalArgumentException("tolerances must not be null");
      }
      if (pricePercent.signum() < 0 || qtyPercent.signum() < 0) {
        throw new IllegalArgumentException("tolerances must not be negative");
      }
    }
  }

  /** One line of the invoice being matched. */
  public record InvoicedLine(UUID variantId, BigDecimal qty, BigDecimal unitPrice) {}

  /** What the order and the receipts say about one variant, before this invoice. */
  public record OrderPosition(
      UUID variantId,
      BigDecimal qtyOrdered,
      BigDecimal qtyReceived,
      BigDecimal qtyAlreadyInvoiced,
      BigDecimal orderedUnitPrice) {}

  /**
   * Matches one invoice against the order and its receipts.
   *
   * <p>Invoiced quantity is compared <b>cumulatively</b> across every invoice on the order, not
   * just this one. A supplier who delivers in two lorries invoices in two documents, and matching
   * each against the whole order in isolation would flag the second as over-invoiced every single
   * time — the same mistake the goods-receipt path made before partial receipt fixed it.
   *
   * @param invoiced the lines on the invoice being captured
   * @param positions what the order and receipts say, keyed by variant
   * @param tolerance how much disagreement is accepted
   * @return one row per invoiced line, in the order supplied
   */
  public static List<MatchLine> match(
      List<InvoicedLine> invoiced, List<OrderPosition> positions, Tolerance tolerance) {

    Map<UUID, OrderPosition> byVariant = new LinkedHashMap<>();
    for (OrderPosition p : positions) {
      byVariant.put(p.variantId(), p);
    }

    List<MatchLine> out = new ArrayList<>(invoiced.size());
    for (InvoicedLine line : invoiced) {
      OrderPosition pos = byVariant.get(line.variantId());
      List<String> variances = new ArrayList<>();

      BigDecimal ordered = pos == null ? BigDecimal.ZERO : pos.qtyOrdered();
      BigDecimal received = pos == null ? BigDecimal.ZERO : pos.qtyReceived();
      BigDecimal before = pos == null ? BigDecimal.ZERO : pos.qtyAlreadyInvoiced();
      BigDecimal orderedPrice = pos == null ? null : pos.orderedUnitPrice();

      if (pos == null) {
        // Billed for something never ordered. Reported on its own rather than as a quantity
        // variance, because the answer is different: this is not "too many", it is "what is this".
        variances.add(NOT_ON_ORDER);
      } else if (received.signum() == 0) {
        variances.add(NOT_RECEIVED);
      } else if (exceeds(before.add(line.qty()), received, tolerance.qtyPercent())) {
        variances.add(INVOICED_ABOVE_RECEIVED);
      }

      if (orderedPrice != null && line.unitPrice() != null) {
        int cmp = comparePrice(line.unitPrice(), orderedPrice, tolerance.pricePercent());
        if (cmp > 0) {
          variances.add(PRICE_ABOVE_ORDER);
        } else if (cmp < 0) {
          variances.add(PRICE_BELOW_ORDER);
        }
      }

      out.add(
          new MatchLine(
              line.variantId(),
              ordered,
              received,
              before,
              line.qty(),
              orderedPrice,
              line.unitPrice(),
              variances));
    }
    return out;
  }

  /**
   * Whether {@code actual} is above {@code allowed} by more than {@code percent} of it.
   *
   * <p>A zero allowance is compared exactly rather than by percentage: a percentage of zero is
   * zero, so any invoiced quantity would exceed it, which is the right answer and worth reaching
   * directly rather than through a multiplication that reads like it might not be.
   */
  private static boolean exceeds(BigDecimal actual, BigDecimal allowed, BigDecimal percent) {
    if (percent.signum() == 0) {
      return actual.compareTo(allowed) > 0;
    }
    BigDecimal band = allowed.multiply(percent).divide(HUNDRED, 6, RoundingMode.HALF_UP);
    return actual.compareTo(allowed.add(band)) > 0;
  }

  /**
   * Compares an invoiced price against the ordered one within tolerance.
   *
   * @return positive if above the band, negative if below it, zero if inside
   */
  private static int comparePrice(BigDecimal invoiced, BigDecimal ordered, BigDecimal percent) {
    if (percent.signum() == 0) {
      return invoiced.compareTo(ordered);
    }
    BigDecimal band = ordered.abs().multiply(percent).divide(HUNDRED, 6, RoundingMode.HALF_UP);
    if (invoiced.compareTo(ordered.add(band)) > 0) return 1;
    if (invoiced.compareTo(ordered.subtract(band)) < 0) return -1;
    return 0;
  }

  private static final BigDecimal HUNDRED = new BigDecimal("100");
}
