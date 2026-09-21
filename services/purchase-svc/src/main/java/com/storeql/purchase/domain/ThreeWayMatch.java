package com.storeql.purchase.domain;

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

    /** Defensively copies {@code variances} so a stored match cannot be edited after the fact. */
    public MatchLine {
      variances = List.copyOf(variances);
    }

    /**
     * Everything billed for this variant on this order, including this invoice.
     *
     * @return earlier invoiced quantity plus this invoice's
     */
    public BigDecimal qtyInvoicedTotal() {
      return qtyInvoicedBefore.add(qtyInvoicedNow);
    }

    /**
     * Whether this line agreed across all three documents.
     *
     * @return {@code true} when no variance was found
     */
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
  /**
   * How much disagreement is accepted, in the shape SAP's tolerance keys use: a percentage
   * <em>and</em> an absolute amount for each check, with separate upper and lower limits on price.
   *
   * <p>The stricter limit governs. A price 5% over the order on a £2 item is 10p, which a 5% band
   * accepts; the same 5% on a £2,000 item is £100, which an absolute limit of £20 refuses. Neither
   * figure alone describes what a buyer will tolerate. An absolute limit of {@code null} means "not
   * checked"; a percentage of zero means exact.
   *
   * @param priceUpperPercent how far above the order's price a line may be, in percent
   * @param priceLowerPercent how far below; a supplier under-charging is still a variance, but a
   *     business may reasonably care less
   * @param priceAbsolute the most a unit price may differ from the order's in money, either way;
   *     null for no absolute limit
   * @param qtyPercent how far above what was received a line may bill, in percent
   * @param qtyAbsolute the most units a line may bill above what was received; null for none
   * @param totalAbsolute how far the supplier's stated total may differ from the sum of their own
   *     lines plus VAT, in money; zero for exact
   */
  public record Tolerance(
      BigDecimal priceUpperPercent,
      BigDecimal priceLowerPercent,
      BigDecimal priceAbsolute,
      BigDecimal qtyPercent,
      BigDecimal qtyAbsolute,
      BigDecimal totalAbsolute) {

    public static final Tolerance EXACT =
        new Tolerance(BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, null, ZERO_TOTAL);

    /** A symmetric percentage band on price and a percentage band on quantity, nothing absolute. */
    public Tolerance(BigDecimal pricePercent, BigDecimal qtyPercent) {
      this(pricePercent, pricePercent, null, qtyPercent, null, ZERO_TOTAL);
    }

    public Tolerance {
      if (priceUpperPercent == null
          || priceLowerPercent == null
          || qtyPercent == null
          || totalAbsolute == null) {
        throw new IllegalArgumentException("tolerances must not be null");
      }
      if (priceUpperPercent.signum() < 0
          || priceLowerPercent.signum() < 0
          || qtyPercent.signum() < 0
          || totalAbsolute.signum() < 0
          || (priceAbsolute != null && priceAbsolute.signum() < 0)
          || (qtyAbsolute != null && qtyAbsolute.signum() < 0)) {
        throw new IllegalArgumentException("tolerances must not be negative");
      }
    }

    /** The symmetric price percentage, for callers that predate the asymmetric form. */
    public BigDecimal pricePercent() {
      return priceUpperPercent;
    }

    /**
     * Whether the supplier's stated total disagrees with the figures by more than is tolerated.
     *
     * @param stated the total printed on the document
     * @param computed the sum of the lines plus VAT
     * @return {@code true} when they differ by more than {@link #totalAbsolute}
     */
    public boolean totalMismatch(BigDecimal stated, BigDecimal computed) {
      return stated.subtract(computed).abs().compareTo(totalAbsolute) > 0;
    }
  }

  private static final BigDecimal ZERO_TOTAL = BigDecimal.ZERO;

  /** The header-level variance: the supplier's own total does not add up. */
  public static final String TOTAL_MISMATCH = "TOTAL_MISMATCH";

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
      } else if (exceeds(
          before.add(line.qty()), received, tolerance.qtyPercent(), tolerance.qtyAbsolute())) {
        variances.add(INVOICED_ABOVE_RECEIVED);
      }

      if (orderedPrice != null && line.unitPrice() != null) {
        int cmp = comparePrice(line.unitPrice(), orderedPrice, tolerance);
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
   * Whether {@code actual} is above {@code allowed} by more than the band allows.
   *
   * <p>A zero percentage is compared exactly rather than by percentage: a percentage of zero is
   * zero, so any excess would exceed it, which is the right answer and worth reaching directly
   * rather than through a multiplication that reads like it might not be. When an absolute limit is
   * also set the narrower of the two bands governs.
   */
  private static boolean exceeds(
      BigDecimal actual, BigDecimal allowed, BigDecimal percent, BigDecimal absolute) {
    return actual.compareTo(allowed.add(band(allowed, percent, absolute))) > 0;
  }

  /**
   * Compares an invoiced price with the ordered one: positive above the upper band, negative below
   * the lower band, zero inside both.
   */
  private static int comparePrice(BigDecimal invoiced, BigDecimal ordered, Tolerance t) {
    BigDecimal base = ordered.abs();
    BigDecimal up = band(base, t.priceUpperPercent(), t.priceAbsolute());
    BigDecimal down = band(base, t.priceLowerPercent(), t.priceAbsolute());
    if (invoiced.compareTo(ordered.add(up)) > 0) return 1;
    if (invoiced.compareTo(ordered.subtract(down)) < 0) return -1;
    return 0;
  }

  /** The allowance: {@code percent} of {@code base}, capped at {@code absolute} when one is set. */
  private static BigDecimal band(BigDecimal base, BigDecimal percent, BigDecimal absolute) {
    BigDecimal byPercent =
        percent.signum() == 0
            ? BigDecimal.ZERO
            : base.multiply(percent).divide(HUNDRED, 6, RoundingMode.HALF_UP);
    if (absolute == null) return byPercent;
    return byPercent.min(absolute);
  }

  private static final BigDecimal HUNDRED = new BigDecimal("100");
}
