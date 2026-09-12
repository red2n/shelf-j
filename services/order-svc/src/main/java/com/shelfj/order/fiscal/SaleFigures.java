package com.shelfj.order.fiscal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a fiscal regime signs about one sale (18.5): the gross taken at each VAT rate and how it was
 * paid. Both are what a German security module signs and what a Portuguese document lists; neither
 * needs the product, the customer or the receipt number.
 *
 * @param orderId the sale
 * @param currency ISO-4217
 * @param startedAt when the sale began — the order's creation, which the till records as the start
 *     of the transaction
 * @param grossByRate gross amount per VAT rate, in percent (19.00, 7.00, 0.00 …)
 * @param tenders how the sale was paid, each with its method (CASH, CARD, …)
 */
public record SaleFigures(
    UUID orderId,
    String currency,
    Instant startedAt,
    List<RateAmount> grossByRate,
    List<TenderAmount> tenders) {

  /** Defensive copies: what a device signs must not change under it. */
  public SaleFigures {
    grossByRate = List.copyOf(grossByRate);
    tenders = List.copyOf(tenders);
  }

  /** Gross taken at one VAT rate. */
  public record RateAmount(BigDecimal ratePercent, BigDecimal gross) {}

  /** One tender, by the method the payment ledger recorded. */
  public record TenderAmount(String method, BigDecimal amount) {

    /** Whether this tender counts as cash for the German file and the device's process data. */
    public boolean isCash() {
      return "CASH".equalsIgnoreCase(method);
    }
  }

  /** The gross of every rate together. */
  public BigDecimal gross() {
    return grossByRate.stream().map(RateAmount::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
