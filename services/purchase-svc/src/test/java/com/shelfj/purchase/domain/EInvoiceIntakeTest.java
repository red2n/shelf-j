package com.shelfj.purchase.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.einvoice.ElectronicAddress;
import com.shelfj.einvoice.Invoice;
import com.shelfj.einvoice.Invoice.Identifier;
import com.shelfj.einvoice.Invoice.Item;
import com.shelfj.einvoice.Invoice.Line;
import com.shelfj.einvoice.Invoice.Party;
import com.shelfj.einvoice.Invoice.Price;
import com.shelfj.einvoice.Invoice.Totals;
import com.shelfj.purchase.domain.EInvoiceIntake.Found;
import com.shelfj.purchase.domain.EInvoiceIntake.ItemCode;
import com.shelfj.purchase.domain.EInvoiceIntake.LineMatch;
import com.shelfj.purchase.domain.EInvoiceIntake.OrderLine;
import com.shelfj.purchase.domain.EInvoiceIntake.ReturnRef;
import com.shelfj.purchase.domain.EInvoiceIntake.SupplierRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a received e-invoice is matched to, decided without a guess: one supplier or none, the order
 * it names, the order line each line references or was taught, and the return a credit note closes.
 */
class EInvoiceIntakeTest {

  private static final UUID ACME = UUID.fromString("0192f000-0000-7000-8000-000000000001");
  private static final UUID OTHER = UUID.fromString("0192f000-0000-7000-8000-000000000002");
  private static final UUID PO = UUID.fromString("0192f000-0000-7000-8000-0000000000a1");
  private static final UUID LINE_1 = UUID.fromString("0192f000-0000-7000-8000-0000000000b1");
  private static final UUID LINE_2 = UUID.fromString("0192f000-0000-7000-8000-0000000000b2");
  private static final UUID APPLES = UUID.fromString("0192f000-0000-7000-8000-0000000000c1");
  private static final UUID PEARS = UUID.fromString("0192f000-0000-7000-8000-0000000000c2");

  @Test
  void theSupplierIsFoundByItsAddressBeforeItsVatNumber() {
    Invoice inv =
        invoice(
            party("Acme GmbH", "DE111111111", new Identifier("DE111111111", "9930")), List.of());
    List<SupplierRef> suppliers =
        List.of(
            new SupplierRef(OTHER, "Acme (old)", "DE111111111", null, null),
            new SupplierRef(ACME, "Acme GmbH", null, "9930", "de111111111"));
    assertEquals(ACME, EInvoiceIntake.supplier(inv, suppliers).value().id());
  }

  @Test
  void aVatNumberSharedByTwoSuppliersIsNoAnswer() {
    Invoice inv = invoice(party("Acme", "DE 111 111 111", null), List.of());
    List<SupplierRef> suppliers =
        List.of(
            new SupplierRef(ACME, "Acme", "DE111111111", null, null),
            new SupplierRef(OTHER, "Acme too", "de111111111", null, null));
    Found<SupplierRef> found = EInvoiceIntake.supplier(inv, suppliers);
    assertNull(found.value());
    assertTrue(found.problem().contains("2 suppliers"), found.problem());
  }

  @Test
  void noSupplierSaysWhatWasLookedFor() {
    Invoice inv =
        invoice(
            party("Stranger Ltd", "GB999999973", new Identifier("GB999999973", "9932")), List.of());
    Found<SupplierRef> found =
        EInvoiceIntake.supplier(
            inv, List.of(new SupplierRef(ACME, "Acme", "DE111111111", null, null)));
    assertNull(found.value());
    assertTrue(
        found.problem().contains("9932:GB999999973") && found.problem().contains("Stranger Ltd"),
        found.problem());
  }

  @Test
  void anInvoiceIsMisdirectedOnlyWhenAComparisonCouldBeMade() {
    Invoice toUs =
        invoice(
            party("S", "DE1", null),
            List.of(),
            party("Us Ltd", "GB123456789", new Identifier("5790000435975", "0088")));
    assertNull(EInvoiceIntake.misdirected(toUs, "GB 123456789", null));
    assertNull(
        EInvoiceIntake.misdirected(toUs, null, new ElectronicAddress("0088", "5790000435975")));
    assertNull(
        EInvoiceIntake.misdirected(toUs, null, null),
        "a business that set nothing is not compared");
    String elsewhere = EInvoiceIntake.misdirected(toUs, "GB987654321", null);
    assertTrue(elsewhere != null && elsewhere.contains("Us Ltd"), elsewhere);
    Invoice noBuyerVat = invoice(party("S", "DE1", null), List.of(), party("Someone", null, null));
    assertNull(
        EInvoiceIntake.misdirected(noBuyerVat, "GB987654321", null), "nothing to compare with");
  }

  @Test
  void theOrderReferenceIsTheOrdersIdWhereverTheSupplierPutIt() {
    assertEquals(PO, EInvoiceIntake.orderReference(withOrder("PO " + PO)).orElseThrow());
    assertEquals(
        PO, EInvoiceIntake.orderReference(withOrder(PO.toString().toUpperCase())).orElseThrow());
    assertTrue(EInvoiceIntake.orderReference(withOrder("4500012345")).isEmpty());
    assertTrue(EInvoiceIntake.orderReference(withOrder(null)).isEmpty());
  }

  @Test
  void linesMatchByReferenceThenByTaughtCodeAndEachOrderLineOnce() {
    List<OrderLine> order =
        List.of(new OrderLine(LINE_1, 1, APPLES), new OrderLine(LINE_2, 2, PEARS));
    Invoice inv =
        invoice(
            party("Acme", null, null),
            List.of(
                line("1", "2", item("Pears", "P-77", null, null)),
                line("2", LINE_1.toString(), item("Apples", null, null, null)),
                line("3", null, item("Apples again", "A-1", null, null))));
    List<LineMatch> matches =
        EInvoiceIntake.lines(
            inv, order, List.of(new ItemCode(EInvoiceIntake.CODE_SELLER, "a-1", APPLES)));
    assertEquals(LINE_2, matches.get(0).poLineId(), "position 2 of the order");
    assertEquals(EInvoiceIntake.MATCHED_BY_ORDER_LINE, matches.get(0).matchedBy());
    assertEquals(LINE_1, matches.get(1).poLineId(), "the order line's own id");
    assertFalse(matches.get(2).matched(), "apples' only order line is taken; a person decides");
  }

  @Test
  void aStandardIdentifierAndTheBuyersOwnVariantIdMatchToo() {
    List<OrderLine> order =
        List.of(new OrderLine(LINE_1, 1, APPLES), new OrderLine(LINE_2, 2, PEARS));
    Invoice inv =
        invoice(
            party("Acme", null, null),
            List.of(
                line(
                    "1", null, item("Apples", null, null, new Identifier("4000001123452", "0160"))),
                line("2", null, item("Pears", null, PEARS.toString(), null)),
                line("3", null, item("Plums", null, UUID.randomUUID().toString(), null))));
    List<LineMatch> matches =
        EInvoiceIntake.lines(
            inv,
            order,
            List.of(new ItemCode(EInvoiceIntake.CODE_STANDARD, "0160:4000001123452", APPLES)));
    assertEquals(LINE_1, matches.get(0).poLineId());
    assertEquals(LINE_2, matches.get(1).poLineId());
    assertFalse(
        matches.get(2).matched(), "a variant id that is not on the order is not ours to take");
  }

  @Test
  void aUnitPriceCarriesTheLinesOwnAllowancesAndBaseQuantity() {
    Line line =
        line(
            "1", new BigDecimal("12"), new BigDecimal("33.00"), null, new BigDecimal("3.00"), null);
    assertEquals(new BigDecimal("2.75"), EInvoiceIntake.unitPrice(line));
    Line free = line("2", BigDecimal.ZERO, BigDecimal.ZERO, null, new BigDecimal("3.00"), null);
    assertEquals(new BigDecimal("3.00"), EInvoiceIntake.unitPrice(free));
  }

  @Test
  void aCreditNoteClosesTheOnlyOpenReturnOrTheOneForItsAmount() {
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    Invoice credit = withTotal(new BigDecimal("24.00"));
    assertEquals(
        r1,
        EInvoiceIntake.creditedReturn(
                credit,
                List.of(
                    new ReturnRef(r1, "RAISED", new BigDecimal("9.99")),
                    new ReturnRef(r2, "CREDITED", new BigDecimal("24.00"))))
            .value()
            .id());
    assertEquals(
        r2,
        EInvoiceIntake.creditedReturn(
                credit,
                List.of(
                    new ReturnRef(r1, "RAISED", new BigDecimal("9.99")),
                    new ReturnRef(r2, "RAISED", new BigDecimal("24.00"))))
            .value()
            .id());
    assertNull(EInvoiceIntake.creditedReturn(credit, List.of()).value());
    assertNull(
        EInvoiceIntake.creditedReturn(
                credit,
                List.of(
                    new ReturnRef(r1, "RAISED", new BigDecimal("24.00")),
                    new ReturnRef(r2, "RAISED", new BigDecimal("24.00"))))
            .value());
  }

  // ── builders ────────────────────────────────────────────────────────────────

  private static Party party(String name, String vat, Identifier address) {
    return new Party(name, null, List.of(), null, vat, null, null, address, null, null);
  }

  private static Item item(String name, String sellers, String buyers, Identifier standard) {
    return new Item(name, null, sellers, buyers, standard, List.of(), null, List.of());
  }

  private static Line line(String id, String orderLineReference, Item item) {
    return line(id, BigDecimal.ONE, BigDecimal.TEN, orderLineReference, BigDecimal.TEN, item);
  }

  private static Line line(
      String id,
      BigDecimal qty,
      BigDecimal amount,
      String orderLineReference,
      BigDecimal price,
      Item item) {
    return new Line(
        id,
        null,
        null,
        qty,
        "C62",
        amount,
        orderLineReference,
        null,
        null,
        List.of(),
        new Price(price, null, null, null, null),
        "S",
        new BigDecimal("20"),
        item);
  }

  private static Invoice invoice(Party seller, List<Line> lines) {
    return invoice(seller, lines, party("Us", null, null));
  }

  private static Invoice invoice(Party seller, List<Line> lines, Party buyer) {
    return invoice("INV-1", "380", null, seller, buyer, null, lines);
  }

  private static Invoice withOrder(String reference) {
    return invoice(
        "INV-1",
        "380",
        reference,
        party("Acme", null, null),
        party("Us", null, null),
        null,
        List.of());
  }

  private static Invoice withTotal(BigDecimal withVat) {
    return invoice(
        "CN-1",
        "381",
        null,
        party("Acme", null, null),
        party("Us", null, null),
        new Totals(
            withVat, null, null, withVat, BigDecimal.ZERO, null, withVat, null, null, withVat),
        List.of());
  }

  /**
   * An invoice with only what a match reads: its number, type, order, parties, totals and lines.
   */
  private static Invoice invoice(
      String number,
      String typeCode,
      String orderReference,
      Party seller,
      Party buyer,
      Totals totals,
      List<Line> lines) {
    return new Invoice(
        Invoice.PEPPOL_BIS_3,
        Invoice.PEPPOL_BILLING_PROFILE,
        number,
        LocalDate.of(2026, 9, 1),
        typeCode,
        "EUR",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        orderReference,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        List.of(),
        seller,
        buyer,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        totals,
        List.of(),
        List.of(),
        lines);
  }
}
