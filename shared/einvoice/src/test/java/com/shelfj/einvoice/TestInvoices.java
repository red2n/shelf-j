package com.shelfj.einvoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * An EN 16931 invoice built by hand, for the writers' tests: every field the writer reads, and no
 * more.
 */
final class TestInvoices {

  private TestInvoices() {}

  static Invoice invoice(
      String number,
      String type,
      String currency,
      Invoice.Party seller,
      Invoice.Party buyer,
      List<Invoice.AllowanceCharge> allowances,
      List<Invoice.Line> lines,
      List<Invoice.PrecedingInvoice> preceding,
      Invoice.Totals totals,
      List<Invoice.VatBreakdown> breakdown) {
    return new Invoice(
        Invoice.EN16931,
        null,
        number,
        LocalDate.of(2026, 9, 15),
        type,
        currency,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        preceding,
        seller,
        buyer,
        null,
        null,
        null,
        null,
        null,
        allowances,
        totals,
        breakdown,
        List.of(),
        lines);
  }

  static Invoice.Totals totals(String net, String gross) {
    return new Invoice.Totals(
        new BigDecimal(net),
        null,
        null,
        new BigDecimal(net),
        null,
        null,
        new BigDecimal(gross),
        null,
        null,
        new BigDecimal(gross));
  }
}
