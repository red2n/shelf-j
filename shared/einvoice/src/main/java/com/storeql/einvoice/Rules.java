package com.storeql.einvoice;

import com.storeql.einvoice.Codes.CodeList;
import com.storeql.einvoice.Invoice.Address;
import com.storeql.einvoice.Invoice.AllowanceCharge;
import com.storeql.einvoice.Invoice.Attribute;
import com.storeql.einvoice.Invoice.Classification;
import com.storeql.einvoice.Invoice.CreditTransfer;
import com.storeql.einvoice.Invoice.Identifier;
import com.storeql.einvoice.Invoice.Item;
import com.storeql.einvoice.Invoice.Line;
import com.storeql.einvoice.Invoice.Party;
import com.storeql.einvoice.Invoice.Payee;
import com.storeql.einvoice.Invoice.PaymentInstructions;
import com.storeql.einvoice.Invoice.Period;
import com.storeql.einvoice.Invoice.PrecedingInvoice;
import com.storeql.einvoice.Invoice.Price;
import com.storeql.einvoice.Invoice.SupportingDocument;
import com.storeql.einvoice.Invoice.TaxRepresentative;
import com.storeql.einvoice.Invoice.Totals;
import com.storeql.einvoice.Invoice.VatBreakdown;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The business rules of EN 16931-1 and of Peppol BIS Billing 3.0, checked against the model.
 *
 * <p>Each rule is the test its schematron states, not a paraphrase: the arithmetic rounds as
 * XPath's {@code round()} does (half towards positive infinity), the VAT amount rules allow the one
 * unit of slack CEN's release does, and Peppol's line arithmetic the 0.02 its {@code u:slack} does.
 * A rule about the XML itself rather than the invoice — two TaxTotals where one is allowed — cannot
 * be broken by a document this module writes, and on reading is left to the syntax.
 */
public final class Rules {

  /** Which rule sets apply. */
  public enum Profile {
    /** EN 16931-1 alone: its core, its calculations, its VAT categories and its code lists. */
    EN16931,
    /** EN 16931 and the Peppol BIS Billing 3.0 rules on top. */
    PEPPOL_BIS_3
  }

  private static final BigDecimal HALF = new BigDecimal("0.5");
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final BigDecimal PEPPOL_SLACK = new BigDecimal("0.02");

  private static final List<String> CATEGORIES =
      List.of("S", "Z", "E", "AE", "K", "G", "O", "L", "M");
  private static final Set<String> CREDIT_TRANSFER = Set.of("30", "58");
  private static final Set<String> DIRECT_DEBIT = Set.of("49", "59");
  private static final Set<String> PEPPOL_INVOICE_TYPES =
      Set.of(
          "71", "80", "82", "84", "102", "218", "219", "326", "331", "380", "382", "383", "384",
          "386", "388", "393", "395", "553", "575", "623", "780", "817", "870", "875", "876",
          "877");
  private static final Pattern PEPPOL_PROFILE =
      Pattern.compile("urn:fdc:peppol\\.eu:2017:poacc:billing:\\d{2}:1\\.0");

  /** A VATEX code Peppol ties to one VAT category, and the rule that says so. */
  private record Exemption(String category, String rule) {}

  private static final Map<String, Exemption> EXEMPTIONS =
      Map.of(
          "VATEX-EU-G", new Exemption("G", "PEPPOL-EN16931-P0104"),
          "VATEX-EU-O", new Exemption("O", "PEPPOL-EN16931-P0105"),
          "VATEX-EU-IC", new Exemption("K", "PEPPOL-EN16931-P0106"),
          "VATEX-EU-AE", new Exemption("AE", "PEPPOL-EN16931-P0107"),
          "VATEX-EU-D", new Exemption("E", "PEPPOL-EN16931-P0108"),
          "VATEX-EU-F", new Exemption("E", "PEPPOL-EN16931-P0109"),
          "VATEX-EU-I", new Exemption("E", "PEPPOL-EN16931-P0110"),
          "VATEX-EU-J", new Exemption("E", "PEPPOL-EN16931-P0111"));

  private Rules() {}

  /** Peppol BIS Billing 3.0 when the document says it is one, EN 16931 otherwise. */
  public static Profile profileOf(Invoice inv) {
    String id = inv.customizationId();
    return id != null && id.strip().startsWith(Invoice.PEPPOL_BIS_3)
        ? Profile.PEPPOL_BIS_3
        : Profile.EN16931;
  }

  /** Every rule of the profile the document declares. */
  public static List<Violation> check(Invoice inv) {
    return check(inv, profileOf(inv));
  }

  /** Every rule of a profile, in rule order within each group. */
  public static List<Violation> check(Invoice inv, Profile profile) {
    List<Violation> out = new ArrayList<>();
    document(inv, out);
    parties(inv, out);
    lines(inv, out);
    documentAllowancesAndCharges(inv, out);
    vatBreakdown(inv, out);
    totals(inv, out);
    payment(inv, out);
    vatCategories(inv, out);
    decimals(inv, out);
    codeLists(inv, out);
    if (profile == Profile.PEPPOL_BIS_3) peppol(inv, out);
    return List.copyOf(out);
  }

  // ── EN 16931 core ─────────────────────────────────────────────────────────────

  private static void document(Invoice inv, List<Violation> out) {
    require(
        out,
        inv.customizationId(),
        "BR-01",
        "An Invoice shall have a Specification identifier (BT-24).");
    require(out, inv.number(), "BR-02", "An Invoice shall have an Invoice number (BT-1).");
    if (inv.issueDate() == null)
      fail(out, "BR-03", "An Invoice shall have an Invoice issue date (BT-2).");
    require(out, inv.typeCode(), "BR-04", "An Invoice shall have an Invoice type code (BT-3).");
    require(out, inv.currency(), "BR-05", "An Invoice shall have an Invoice currency code (BT-5).");
    if (inv.lines().isEmpty())
      fail(out, "BR-16", "An Invoice shall have at least one Invoice line (BG-25).");
    if (inv.taxPointDate() != null && present(inv.taxPointDateCode())) {
      fail(
          out,
          "BR-CO-03",
          "Value added tax point date (BT-7) and Value added tax point date code (BT-8) are mutually exclusive.");
    }
    period(out, inv.invoicingPeriod(), "BR-CO-19", "BR-29", "Invoicing period (BG-14)");
    for (PrecedingInvoice p : inv.precedingInvoices()) {
      require(
          out,
          p.number(),
          "BR-55",
          "Each Preceding Invoice reference (BG-3) shall contain a Preceding Invoice reference (BT-25).");
    }
    for (SupportingDocument d : inv.supportingDocuments()) {
      require(
          out,
          d.reference(),
          "BR-52",
          "Each Additional supporting document (BG-24) shall contain a Supporting document reference (BT-122).");
    }
    if (inv.delivery() != null && inv.delivery().address() != null) {
      require(
          out,
          inv.delivery().address().country(),
          "BR-57",
          "Each Deliver to address (BG-15) shall contain a Deliver to country code (BT-80).");
    }
    if (present(inv.taxCurrency())
        && (inv.totals() == null || inv.totals().vatInTaxCurrency() == null)) {
      fail(
          out,
          "BR-53",
          "If the VAT accounting currency code (BT-6) is present, then the Invoice total VAT amount in accounting currency (BT-111) shall be provided.");
    }
  }

  private static void period(
      List<Violation> out, Period p, String emptyRule, String orderRule, String what) {
    if (p == null) return;
    if (p.start() == null && p.end() == null) {
      fail(
          out,
          emptyRule,
          "If " + what + " is used, its start date or its end date shall be filled, or both.");
    } else if (p.start() != null && p.end() != null && p.end().isBefore(p.start())) {
      fail(
          out,
          orderRule,
          "The end date of "
              + what
              + " ("
              + p.end()
              + ") shall be later or equal to its start date ("
              + p.start()
              + ").");
    }
  }

  private static void parties(Invoice inv, List<Violation> out) {
    Party seller = inv.seller();
    Party buyer = inv.buyer();
    if (seller == null || !present(seller.name()))
      fail(out, "BR-06", "An Invoice shall contain the Seller name (BT-27).");
    if (buyer == null || !present(buyer.name()))
      fail(out, "BR-07", "An Invoice shall contain the Buyer name (BT-44).");
    if (seller == null || seller.address() == null) {
      fail(out, "BR-08", "An Invoice shall contain the Seller postal address (BG-5).");
    } else {
      require(
          out,
          seller.address().country(),
          "BR-09",
          "The Seller postal address (BG-5) shall contain a Seller country code (BT-40).");
    }
    if (buyer == null || buyer.address() == null) {
      fail(out, "BR-10", "An Invoice shall contain the Buyer postal address (BG-8).");
    } else {
      require(
          out,
          buyer.address().country(),
          "BR-11",
          "The Buyer postal address shall contain a Buyer country code (BT-55).");
    }
    if (seller != null) {
      if (seller.electronicAddress() != null) {
        require(
            out,
            seller.electronicAddress().scheme(),
            "BR-62",
            "The Seller electronic address (BT-34) shall have a Scheme identifier.");
      }
      if (seller.identifiers().isEmpty()
          && seller.legalRegistration() == null
          && !present(seller.vatId())) {
        fail(
            out,
            "BR-CO-26",
            "In order for the buyer to automatically identify a supplier, the Seller identifier (BT-29), the Seller legal registration identifier (BT-30) and/or the Seller VAT identifier (BT-31) shall be present.");
      }
      vatPrefix(out, seller.vatId(), "Seller VAT identifier (BT-31)");
    }
    if (buyer != null) {
      if (buyer.electronicAddress() != null) {
        require(
            out,
            buyer.electronicAddress().scheme(),
            "BR-63",
            "The Buyer electronic address (BT-49) shall have a Scheme identifier.");
      }
      vatPrefix(out, buyer.vatId(), "Buyer VAT identifier (BT-48)");
    }
    Payee payee = inv.payee();
    if (payee != null) {
      require(
          out,
          payee.name(),
          "BR-17",
          "The Payee name (BT-59) shall be provided in the Invoice, if the Payee (BG-10) is different from the Seller (BG-4).");
    }
    TaxRepresentative rep = inv.taxRepresentative();
    if (rep != null) {
      require(
          out,
          rep.name(),
          "BR-18",
          "The Seller tax representative name (BT-62) shall be provided in the Invoice, if the Seller (BG-4) has a Seller tax representative party (BG-11).");
      if (rep.address() == null) {
        fail(
            out,
            "BR-19",
            "The Seller tax representative postal address (BG-12) shall be provided in the Invoice, if the Seller (BG-4) has a Seller tax representative party (BG-11).");
      } else {
        require(
            out,
            rep.address().country(),
            "BR-20",
            "The Seller tax representative postal address (BG-12) shall contain a Tax representative country code (BT-69).");
      }
      require(
          out,
          rep.vatId(),
          "BR-56",
          "Each Seller tax representative party (BG-11) shall have a Seller tax representative VAT identifier (BT-63).");
      vatPrefix(out, rep.vatId(), "Seller tax representative VAT identifier (BT-63)");
    }
  }

  private static void vatPrefix(List<Violation> out, String vatId, String what) {
    if (!present(vatId)) return;
    String id = vatId.strip();
    String prefix = id.length() < 2 ? id : id.substring(0, 2);
    if (!"EL".equals(prefix) && !Codes.contains(CodeList.COUNTRIES, prefix)) {
      fail(
          out,
          "BR-CO-09",
          "The "
              + what
              + " shall have a prefix in accordance with ISO 3166-1 alpha-2 by which the country of issue may be identified: \""
              + id
              + "\".");
    }
  }

  private static void lines(Invoice inv, List<Violation> out) {
    for (Line l : inv.lines()) {
      String at = " (line " + (l.id() == null ? "without an identifier" : l.id()) + ")";
      require(
          out,
          l.id(),
          "BR-21",
          "Each Invoice line (BG-25) shall have an Invoice line identifier (BT-126).");
      if (l.quantity() == null)
        fail(
            out,
            "BR-22",
            "Each Invoice line (BG-25) shall have an Invoiced quantity (BT-129)" + at + ".");
      require(
          out,
          l.unitCode(),
          "BR-23",
          "An Invoice line (BG-25) shall have an Invoiced quantity unit of measure code (BT-130)"
              + at
              + ".");
      if (l.netAmount() == null)
        fail(
            out,
            "BR-24",
            "Each Invoice line (BG-25) shall have an Invoice line net amount (BT-131)" + at + ".");
      if (l.item() == null || !present(l.item().name()))
        fail(
            out,
            "BR-25",
            "Each Invoice line (BG-25) shall contain the Item name (BT-153)" + at + ".");
      Price price = l.price();
      if (price == null || price.net() == null) {
        fail(
            out,
            "BR-26",
            "Each Invoice line (BG-25) shall contain the Item net price (BT-146)" + at + ".");
      } else if (price.net().signum() < 0) {
        fail(out, "BR-27", "The Item net price (BT-146) shall NOT be negative" + at + ".");
      }
      if (price != null && price.gross() != null && price.gross().signum() < 0) {
        fail(out, "BR-28", "The Item gross price (BT-148) shall NOT be negative" + at + ".");
      }
      require(
          out,
          l.vatCategory(),
          "BR-CO-04",
          "Each Invoice line (BG-25) shall be categorized with an Invoiced item VAT category code (BT-151)"
              + at
              + ".");
      period(out, l.period(), "BR-CO-20", "BR-30", "Invoice line period (BG-26)" + at);
      for (AllowanceCharge ac : l.allowanceCharges()) {
        boolean reasoned = present(ac.reason()) || present(ac.reasonCode());
        if (ac.charge()) {
          if (ac.amount() == null)
            fail(
                out,
                "BR-43",
                "Each Invoice line charge (BG-28) shall have an Invoice line charge amount (BT-141)"
                    + at
                    + ".");
          if (!reasoned) {
            fail(
                out,
                "BR-44",
                "Each Invoice line charge shall have an Invoice line charge reason or an invoice line allowance reason code"
                    + at
                    + ".");
            fail(
                out,
                "BR-CO-24",
                "Each Invoice line charge (BG-28) shall contain an Invoice line charge reason (BT-144) or an Invoice line charge reason code (BT-145), or both"
                    + at
                    + ".");
          }
        } else {
          if (ac.amount() == null)
            fail(
                out,
                "BR-41",
                "Each Invoice line allowance (BG-27) shall have an Invoice line allowance amount (BT-136)"
                    + at
                    + ".");
          if (!reasoned) {
            fail(
                out,
                "BR-42",
                "Each Invoice line allowance (BG-27) shall have an Invoice line allowance reason (BT-139) or an Invoice line allowance reason code (BT-140)"
                    + at
                    + ".");
            fail(
                out,
                "BR-CO-23",
                "Each Invoice line allowance (BG-27) shall contain an Invoice line allowance reason (BT-139) or an Invoice line allowance reason code (BT-140), or both"
                    + at
                    + ".");
          }
        }
      }
      Item item = l.item();
      if (item == null) continue;
      if (item.standardId() != null) {
        require(
            out,
            item.standardId().scheme(),
            "BR-64",
            "The Item standard identifier (BT-157) shall have a Scheme identifier" + at + ".");
      }
      for (Classification c : item.classifications()) {
        require(
            out,
            c.scheme(),
            "BR-65",
            "The Item classification identifier (BT-158) shall have a Scheme identifier"
                + at
                + ".");
      }
      for (Attribute a : item.attributes()) {
        if (!present(a.name()) || !present(a.value())) {
          fail(
              out,
              "BR-54",
              "Each Item attribute (BG-32) shall contain an Item attribute name (BT-160) and an Item attribute value (BT-161)"
                  + at
                  + ".");
        }
      }
    }
  }

  private static void documentAllowancesAndCharges(Invoice inv, List<Violation> out) {
    for (AllowanceCharge ac : inv.allowanceCharges()) {
      boolean reasoned = present(ac.reason()) || present(ac.reasonCode());
      if (ac.charge()) {
        if (ac.amount() == null)
          fail(
              out,
              "BR-36",
              "Each Document level charge (BG-21) shall have a Document level charge amount (BT-99).");
        require(
            out,
            ac.vatCategory(),
            "BR-37",
            "Each Document level charge (BG-21) shall have a Document level charge VAT category code (BT-102).");
        if (!reasoned) {
          fail(
              out,
              "BR-38",
              "Each Document level charge (BG-21) shall have a Document level charge reason (BT-104) or a Document level charge reason code (BT-105).");
          fail(
              out,
              "BR-CO-22",
              "Each Document level charge (BG-21) shall contain a Document level charge reason (BT-104) or a Document level charge reason code (BT-105), or both.");
        }
      } else {
        if (ac.amount() == null)
          fail(
              out,
              "BR-31",
              "Each Document level allowance (BG-20) shall have a Document level allowance amount (BT-92).");
        require(
            out,
            ac.vatCategory(),
            "BR-32",
            "Each Document level allowance (BG-20) shall have a Document level allowance VAT category code (BT-95).");
        if (!reasoned) {
          fail(
              out,
              "BR-33",
              "Each Document level allowance (BG-20) shall have a Document level allowance reason (BT-97) or a Document level allowance reason code (BT-98).");
          fail(
              out,
              "BR-CO-21",
              "Each Document level allowance (BG-20) shall contain a Document level allowance reason (BT-97) or a Document level allowance reason code (BT-98), or both.");
        }
      }
    }
  }

  private static void vatBreakdown(Invoice inv, List<Violation> out) {
    if (inv.vatBreakdown().isEmpty()) {
      fail(out, "BR-CO-18", "An Invoice shall at least have one VAT breakdown group (BG-23).");
    }
    for (VatBreakdown b : inv.vatBreakdown()) {
      if (b.taxableAmount() == null)
        fail(
            out,
            "BR-45",
            "Each VAT breakdown (BG-23) shall have a VAT category taxable amount (BT-116).");
      if (b.taxAmount() == null)
        fail(
            out,
            "BR-46",
            "Each VAT breakdown (BG-23) shall have a VAT category tax amount (BT-117).");
      require(
          out,
          b.category(),
          "BR-47",
          "Each VAT breakdown (BG-23) shall be defined through a VAT category code (BT-118).");
      if (b.rate() == null && !"O".equals(norm(b.category()))) {
        fail(
            out,
            "BR-48",
            "Each VAT breakdown (BG-23) shall have a VAT category rate (BT-119), except if the Invoice is not subject to VAT.");
      }
      if (b.taxAmount() != null && b.taxableAmount() != null) vatAmountRounds(b, out);
    }
  }

  /** BR-CO-17, with the unit of slack CEN's schematron allows. */
  private static void vatAmountRounds(VatBreakdown b, List<Violation> out) {
    boolean ok;
    if (b.rate() == null || roundInteger(b.rate()).signum() == 0) {
      ok = roundInteger(b.taxAmount()).signum() == 0;
    } else {
      ok =
          withinOneUnit(
              b.taxAmount().abs(),
              round2(b.taxableAmount().abs().multiply(b.rate()).divide(HUNDRED)));
    }
    if (!ok) {
      fail(
          out,
          "BR-CO-17",
          "VAT category tax amount (BT-117) = VAT category taxable amount (BT-116) x (VAT category rate (BT-119) / 100), rounded to two decimals: "
              + b.taxableAmount().toPlainString()
              + " at "
              + (b.rate() == null ? "no rate" : b.rate().toPlainString() + "%")
              + " is not "
              + b.taxAmount().toPlainString()
              + ".");
    }
  }

  private static void totals(Invoice inv, List<Violation> out) {
    Totals t = inv.totals();
    if (t == null || t.lineNet() == null)
      fail(out, "BR-12", "An Invoice shall have the Sum of Invoice line net amount (BT-106).");
    if (t == null || t.withoutVat() == null)
      fail(out, "BR-13", "An Invoice shall have the Invoice total amount without VAT (BT-109).");
    if (t == null || t.withVat() == null)
      fail(out, "BR-14", "An Invoice shall have the Invoice total amount with VAT (BT-112).");
    if (t == null || t.payable() == null)
      fail(out, "BR-15", "An Invoice shall have the Amount due for payment (BT-115).");
    if (t == null) return;

    if (t.lineNet() != null) {
      BigDecimal sum = BigDecimal.ZERO;
      for (Line l : inv.lines()) {
        if (l.netAmount() != null) sum = sum.add(l.netAmount());
      }
      if (t.lineNet().compareTo(round2(sum)) != 0) {
        fail(
            out,
            "BR-CO-10",
            "Sum of Invoice line net amount (BT-106) = Σ Invoice line net amount (BT-131): "
                + t.lineNet().toPlainString()
                + " is not "
                + round2(sum).toPlainString()
                + ".");
      }
    }
    documentSum(
        out,
        t.allowances(),
        inv.allowances(),
        "BR-CO-11",
        "Sum of allowances on document level (BT-107) = Σ Document level allowance amount (BT-92)");
    documentSum(
        out,
        t.charges(),
        inv.charges(),
        "BR-CO-12",
        "Sum of charges on document level (BT-108) = Σ Document level charge amount (BT-99)");

    if (t.withoutVat() != null && t.lineNet() != null) {
      BigDecimal expected =
          t.allowances() == null && t.charges() == null
              ? t.lineNet()
              : round2(t.lineNet().add(orZero(t.charges())).subtract(orZero(t.allowances())));
      if (t.withoutVat().compareTo(expected) != 0) {
        fail(
            out,
            "BR-CO-13",
            "Invoice total amount without VAT (BT-109) = Σ Invoice line net amount (BT-131) - Sum of allowances on document level (BT-107) + Sum of charges on document level (BT-108): "
                + t.withoutVat().toPlainString()
                + " is not "
                + expected.toPlainString()
                + ".");
      }
    }
    // BR-CO-14 is stated on the total VAT amount, so an invoice that gives none (CII allows it for
    // an
    // invoice with no VAT to total) is not tested; UBL's schema requires one.
    if (t.vat() != null && !inv.vatBreakdown().isEmpty()) {
      BigDecimal sum = BigDecimal.ZERO;
      for (VatBreakdown b : inv.vatBreakdown()) {
        if (b.taxAmount() != null) sum = sum.add(b.taxAmount());
      }
      if (t.vat().compareTo(round2(sum)) != 0) {
        fail(
            out,
            "BR-CO-14",
            "Invoice total VAT amount (BT-110) = Σ VAT category tax amount (BT-117): "
                + t.vat().toPlainString()
                + " is not "
                + round2(sum).toPlainString()
                + ".");
      }
    }
    // BR-CO-15 as CEN's CII binding tests it: the total with VAT adds the VAT, or equals the total
    // without it.
    boolean addsVat =
        t.vat() != null
            && t.withVat() != null
            && t.withoutVat() != null
            && t.withVat().compareTo(round2(t.withoutVat().add(t.vat()))) == 0;
    boolean noVatToAdd =
        t.withVat() != null && t.withoutVat() != null && t.withVat().compareTo(t.withoutVat()) == 0;
    if (t.withVat() != null && t.withoutVat() != null && !addsVat && !noVatToAdd) {
      fail(
          out,
          "BR-CO-15",
          "Invoice total amount with VAT (BT-112) = Invoice total amount without VAT (BT-109) + Invoice total VAT amount (BT-110).");
    }
    if (t.payable() != null && t.withVat() != null && !payableAddsUp(t)) {
      fail(
          out,
          "BR-CO-16",
          "Amount due for payment (BT-115) = Invoice total amount with VAT (BT-112) - Paid amount (BT-113) + Rounding amount (BT-114).");
    }
    // EN 16931-1 states BR-CO-25, but neither CEN's schematron release nor Peppol's tests it —
    // CEN's
    // own credit note example breaks it — so an access point accepts what breaks it: a warning.
    if (t.payable() != null
        && t.payable().signum() > 0
        && inv.dueDate() == null
        && !present(inv.paymentTerms())) {
      out.add(
          Violation.warning(
              "BR-CO-25",
              "In case the Amount due for payment (BT-115) is positive, either the Payment due date (BT-9) or the Payment terms (BT-20) shall be present."));
    }
  }

  private static void documentSum(
      List<Violation> out,
      BigDecimal total,
      List<AllowanceCharge> items,
      String rule,
      String statement) {
    BigDecimal sum = BigDecimal.ZERO;
    for (AllowanceCharge ac : items) {
      if (ac.amount() != null) sum = sum.add(ac.amount());
    }
    boolean ok = total == null ? items.isEmpty() : total.compareTo(round2(sum)) == 0;
    if (!ok) fail(out, rule, statement + ".");
  }

  private static boolean payableAddsUp(Totals t) {
    BigDecimal withVat = t.withVat();
    BigDecimal payable = t.payable();
    if (t.rounding() == null) {
      return t.paid() == null
          ? payable.compareTo(withVat) == 0
          : payable.compareTo(round2(withVat.subtract(t.paid()))) == 0;
    }
    BigDecimal unrounded = round2(payable.subtract(t.rounding()));
    return t.paid() == null
        ? unrounded.compareTo(withVat) == 0
        : unrounded.compareTo(round2(withVat.subtract(t.paid()))) == 0;
  }

  private static void payment(Invoice inv, List<Violation> out) {
    PaymentInstructions p = inv.payment();
    if (p == null) return;
    require(
        out,
        p.meansCode(),
        "BR-49",
        "A Payment instruction (BG-16) shall specify the Payment means type code (BT-81).");
    for (CreditTransfer t : p.creditTransfers()) {
      require(
          out,
          t.account(),
          "BR-50",
          "A Payment account identifier (BT-84) shall be present if Credit transfer (BG-17) information is provided in the Invoice.");
    }
    if (CREDIT_TRANSFER.contains(norm(p.meansCode()))
        && p.creditTransfers().stream().noneMatch(t -> present(t.account()))) {
      fail(
          out,
          "BR-61",
          "If the Payment means type code (BT-81) means SEPA credit transfer, Local credit transfer or Non-SEPA international credit transfer, the Payment account identifier (BT-84) shall be present.");
    }
    if (p.card() != null
        && present(p.card().primaryAccountNumber())
        && p.card().primaryAccountNumber().strip().length() > 10) {
      out.add(
          Violation.warning(
              "BR-51",
              "In accordance with card payments security standards an invoice should never include a full card primary account number (BT-87)."));
    }
  }

  // ── VAT categories: BR-S, BR-Z, BR-E, BR-AE, BR-IC, BR-G, BR-O, BR-AF, BR-AG, BR-B ──

  private static void vatCategories(Invoice inv, List<Violation> out) {
    for (String code : CATEGORIES) {
      String p = rulePrefix(code);
      String name = categoryName(code);
      List<Line> lines =
          inv.lines().stream().filter(l -> code.equals(norm(l.vatCategory()))).toList();
      List<AllowanceCharge> allowances =
          inv.allowances().stream().filter(a -> code.equals(norm(a.vatCategory()))).toList();
      List<AllowanceCharge> charges =
          inv.charges().stream().filter(c -> code.equals(norm(c.vatCategory()))).toList();
      List<VatBreakdown> breakdowns =
          inv.vatBreakdown().stream().filter(b -> code.equals(norm(b.category()))).toList();
      boolean used = !lines.isEmpty() || !allowances.isEmpty() || !charges.isEmpty();
      boolean rated = isRated(code);

      boolean breakdownPresent =
          rated ? used == !breakdowns.isEmpty() : !used || breakdowns.size() == 1;
      if (!breakdownPresent) {
        fail(
            out,
            p + "-01",
            "An Invoice that contains an Invoice line, a Document level allowance or a Document level charge where the VAT category code is \""
                + name
                + "\" shall contain "
                + (rated ? "at least one" : "exactly one")
                + " VAT breakdown (BG-23) with that VAT category code"
                + (rated ? ", and no such breakdown otherwise." : "."));
      }
      String identity = identityProblem(code, inv);
      if (identity != null) {
        if (!lines.isEmpty())
          fail(
              out,
              p + "-02",
              "An Invoice that contains an Invoice line where the VAT category code is \""
                  + name
                  + "\" "
                  + identity);
        if (!allowances.isEmpty())
          fail(
              out,
              p + "-03",
              "An Invoice that contains a Document level allowance where the VAT category code is \""
                  + name
                  + "\" "
                  + identity);
        if (!charges.isEmpty())
          fail(
              out,
              p + "-04",
              "An Invoice that contains a Document level charge where the VAT category code is \""
                  + name
                  + "\" "
                  + identity);
      }
      for (Line l : lines) {
        if (!rateAllowed(code, l.vatRate()))
          fail(
              out,
              p + "-05",
              "In an Invoice line ("
                  + l.id()
                  + ") where the VAT category code is \""
                  + name
                  + "\" the Invoiced item VAT rate (BT-152) "
                  + rateRequirement(code)
                  + ".");
      }
      for (AllowanceCharge a : allowances) {
        if (!rateAllowed(code, a.vatRate()))
          fail(
              out,
              p + "-06",
              "In a Document level allowance where the VAT category code is \""
                  + name
                  + "\" the Document level allowance VAT rate (BT-96) "
                  + rateRequirement(code)
                  + ".");
      }
      for (AllowanceCharge c : charges) {
        if (!rateAllowed(code, c.vatRate()))
          fail(
              out,
              p + "-07",
              "In a Document level charge where the VAT category code is \""
                  + name
                  + "\" the Document level charge VAT rate (BT-103) "
                  + rateRequirement(code)
                  + ".");
      }
      for (VatBreakdown b : breakdowns) categoryBreakdown(inv, code, b, out);
      if ("O".equals(code) && !breakdowns.isEmpty()) notSubjectAlone(inv, out);
      if ("K".equals(code) && !breakdowns.isEmpty()) intraCommunity(inv, out);
    }
    splitPayment(inv, out);
  }

  private static void categoryBreakdown(
      Invoice inv, String code, VatBreakdown b, List<Violation> out) {
    String p = rulePrefix(code);
    String name = categoryName(code);
    boolean rated = isRated(code);
    if (b.taxableAmount() != null && (!rated || b.rate() != null)) {
      BigDecimal sum = taxableFor(inv, code, rated ? b.rate() : null);
      boolean ok =
          rated
              ? strictlyWithinOneUnit(b.taxableAmount(), sum)
              : b.taxableAmount().compareTo(sum) == 0;
      if (!ok) {
        fail(
            out,
            p + "-08",
            "In a VAT breakdown (BG-23) where the VAT category code is \""
                + name
                + "\""
                + (rated ? " and the rate " + b.rate().toPlainString() + "%" : "")
                + " the VAT category taxable amount (BT-116) shall equal the sum of Invoice line net amounts minus document level allowances plus document level charges with that category: "
                + b.taxableAmount().toPlainString()
                + " is not "
                + sum.toPlainString()
                + ".");
      }
    }
    if (b.taxAmount() != null) {
      boolean ok;
      if (rated) {
        ok =
            b.taxableAmount() == null
                || b.rate() == null
                || withinOneUnit(
                    b.taxAmount().abs(),
                    round2(b.taxableAmount().abs().multiply(b.rate()).divide(HUNDRED)));
      } else {
        ok = b.taxAmount().signum() == 0;
      }
      if (!ok) {
        fail(
            out,
            p + "-09",
            "The VAT category tax amount (BT-117) in a VAT breakdown (BG-23) where the VAT category code is \""
                + name
                + "\" "
                + (rated
                    ? "shall equal the VAT category taxable amount (BT-116) multiplied by the VAT category rate (BT-119)."
                    : "shall be 0 (zero)."));
      }
    }
    boolean exempted = present(b.exemptionReason()) || present(b.exemptionReasonCode());
    boolean mustExplain = !rated && !"Z".equals(code);
    if (mustExplain && !exempted) {
      fail(
          out,
          p + "-10",
          "A VAT breakdown (BG-23) with VAT Category code \""
              + name
              + "\" shall have a VAT exemption reason code (BT-121) or a VAT exemption reason text (BT-120).");
    } else if (!mustExplain && exempted) {
      fail(
          out,
          p + "-10",
          "A VAT breakdown (BG-23) with VAT Category code \""
              + name
              + "\" shall not have a VAT exemption reason code (BT-121) or VAT exemption reason text (BT-120).");
    }
  }

  private static void notSubjectAlone(Invoice inv, List<Violation> out) {
    if (inv.vatBreakdown().stream().anyMatch(b -> !"O".equals(norm(b.category())))) {
      fail(
          out,
          "BR-O-11",
          "An Invoice that contains a VAT breakdown group (BG-23) with a VAT category code (BT-118) \"Not subject to VAT\" shall not contain other VAT breakdown groups (BG-23).");
    }
    if (inv.lines().stream().anyMatch(l -> !"O".equals(norm(l.vatCategory())))) {
      fail(
          out,
          "BR-O-12",
          "An Invoice that contains a VAT breakdown group (BG-23) with a VAT category code (BT-118) \"Not subject to VAT\" shall not contain an Invoice line (BG-25) where the Invoiced item VAT category code (BT-151) is not \"Not subject to VAT\".");
    }
    if (inv.allowances().stream().anyMatch(a -> !"O".equals(norm(a.vatCategory())))) {
      fail(
          out,
          "BR-O-13",
          "An Invoice that contains a VAT breakdown group (BG-23) with a VAT category code (BT-118) \"Not subject to VAT\" shall not contain Document level allowances (BG-20) where Document level allowance VAT category code (BT-95) is not \"Not subject to VAT\".");
    }
    if (inv.charges().stream().anyMatch(c -> !"O".equals(norm(c.vatCategory())))) {
      fail(
          out,
          "BR-O-14",
          "An Invoice that contains a VAT breakdown group (BG-23) with a VAT category code (BT-118) \"Not subject to VAT\" shall not contain Document level charges (BG-21) where Document level charge VAT category code (BT-102) is not \"Not subject to VAT\".");
    }
  }

  private static void intraCommunity(Invoice inv, List<Violation> out) {
    boolean delivered = inv.delivery() != null && inv.delivery().actualDate() != null;
    boolean invoicedPeriod =
        inv.invoicingPeriod() != null
            && (inv.invoicingPeriod().start() != null || inv.invoicingPeriod().end() != null);
    if (!delivered && !invoicedPeriod) {
      fail(
          out,
          "BR-IC-11",
          "In an Invoice with a VAT breakdown (BG-23) where the VAT category code (BT-118) is \"Intra-community supply\" the Actual delivery date (BT-72) or the Invoicing period (BG-14) shall not be blank.");
    }
    Address to = inv.delivery() == null ? null : inv.delivery().address();
    if (to == null || !present(to.country())) {
      fail(
          out,
          "BR-IC-12",
          "In an Invoice with a VAT breakdown (BG-23) where the VAT category code (BT-118) is \"Intra-community supply\" the Deliver to country code (BT-80) shall not be blank.");
    }
  }

  private static void splitPayment(Invoice inv, List<Violation> out) {
    boolean split = usesCategory(inv, "B");
    if (!split) return;
    List<String> countries = new ArrayList<>();
    for (Address a :
        new Address[] {
          address(inv.seller()),
          address(inv.buyer()),
          inv.taxRepresentative() == null ? null : inv.taxRepresentative().address(),
          inv.delivery() == null ? null : inv.delivery().address()
        }) {
      if (a != null && present(a.country())) countries.add(norm(a.country()));
    }
    if (countries.stream().anyMatch(c -> !"IT".equals(c))) {
      fail(
          out,
          "BR-B-01",
          "An Invoice where the VAT category code (BT-151, BT-95 or BT-102) is \"Split payment\" shall be a domestic Italian invoice.");
    }
    if (usesCategory(inv, "S")) {
      fail(
          out,
          "BR-B-02",
          "An Invoice that contains an Invoice line (BG-25), a Document level allowance (BG-20) or a Document level charge (BG-21) where the VAT category code (BT-151, BT-95, BT-118 or BT-102) is \"Split payment\" shall not contain an invoice line (BG-25), a Document level allowance (BG-20) or a Document level charge (BG-21) where the VAT category code (BT-151, BT-95 or BT-102) is \"Standard rated\".");
    }
  }

  private static boolean usesCategory(Invoice inv, String code) {
    return inv.lines().stream().anyMatch(l -> code.equals(norm(l.vatCategory())))
        || inv.allowanceCharges().stream().anyMatch(a -> code.equals(norm(a.vatCategory())))
        || inv.vatBreakdown().stream().anyMatch(b -> code.equals(norm(b.category())));
  }

  private static Address address(Party p) {
    return p == null ? null : p.address();
  }

  private static BigDecimal taxableFor(Invoice inv, String code, BigDecimal rate) {
    BigDecimal sum = BigDecimal.ZERO;
    for (Line l : inv.lines()) {
      if (code.equals(norm(l.vatCategory()))
          && sameRate(rate, l.vatRate())
          && l.netAmount() != null) {
        sum = sum.add(l.netAmount());
      }
    }
    for (AllowanceCharge ac : inv.allowanceCharges()) {
      if (code.equals(norm(ac.vatCategory()))
          && sameRate(rate, ac.vatRate())
          && ac.amount() != null) {
        sum = ac.charge() ? sum.add(ac.amount()) : sum.subtract(ac.amount());
      }
    }
    return sum;
  }

  private static boolean sameRate(BigDecimal rate, BigDecimal other) {
    return rate == null || other != null && rate.compareTo(other) == 0;
  }

  private static String rulePrefix(String code) {
    return switch (code) {
      case "K" -> "BR-IC";
      case "L" -> "BR-AF";
      case "M" -> "BR-AG";
      default -> "BR-" + code;
    };
  }

  private static String categoryName(String code) {
    return switch (code) {
      case "S" -> "Standard rated";
      case "Z" -> "Zero rated";
      case "E" -> "Exempt from VAT";
      case "AE" -> "Reverse charge";
      case "K" -> "Intra-community supply";
      case "G" -> "Export outside the EU";
      case "O" -> "Not subject to VAT";
      case "L" -> "IGIC";
      case "M" -> "IPSI";
      default -> code;
    };
  }

  /** Standard, IGIC and IPSI are charged at a rate; the rest carry none. */
  private static boolean isRated(String code) {
    return "S".equals(code) || "L".equals(code) || "M".equals(code);
  }

  private static boolean rateAllowed(String code, BigDecimal rate) {
    return switch (code) {
      case "S" -> rate != null && rate.signum() > 0;
      case "L", "M" -> rate != null && rate.signum() >= 0;
      case "O" -> rate == null;
      default -> rate != null && rate.signum() == 0;
    };
  }

  private static String rateRequirement(String code) {
    return switch (code) {
      case "S" -> "shall be greater than zero";
      case "L", "M" -> "shall be 0 (zero) or greater than zero";
      case "O" -> "shall not be given";
      default -> "shall be 0 (zero)";
    };
  }

  /** What the category requires of the parties' tax identities, or {@code null} when it has it. */
  private static String identityProblem(String code, Invoice inv) {
    boolean sellerVat = inv.seller() != null && present(inv.seller().vatId());
    boolean sellerTax = inv.seller() != null && present(inv.seller().taxRegistrationId());
    boolean repVat = inv.taxRepresentative() != null && present(inv.taxRepresentative().vatId());
    boolean buyerVat = inv.buyer() != null && present(inv.buyer().vatId());
    boolean buyerLegal = inv.buyer() != null && inv.buyer().legalRegistration() != null;
    return switch (code) {
      case "AE" ->
          (sellerVat || sellerTax || repVat) && (buyerVat || buyerLegal)
              ? null
              : "shall contain the Seller VAT Identifier (BT-31), the Seller Tax registration identifier (BT-32) and/or the Seller tax representative VAT identifier (BT-63) and the Buyer VAT identifier (BT-48) and/or the Buyer legal registration identifier (BT-47).";
      case "K" ->
          (sellerVat || repVat) && buyerVat
              ? null
              : "shall contain the Seller VAT Identifier (BT-31) or the Seller tax representative VAT identifier (BT-63) and the Buyer VAT identifier (BT-48).";
      case "G" ->
          sellerVat || repVat
              ? null
              : "shall contain the Seller VAT Identifier (BT-31) or the Seller tax representative VAT identifier (BT-63).";
      case "O" ->
          !sellerVat && !repVat && !buyerVat
              ? null
              : "shall not contain the Seller VAT identifier (BT-31), the Seller tax representative VAT identifier (BT-63) or the Buyer VAT identifier (BT-48).";
      default ->
          sellerVat || sellerTax || repVat
              ? null
              : "shall contain the Seller VAT Identifier (BT-31), the Seller tax registration identifier (BT-32) and/or the Seller tax representative VAT identifier (BT-63).";
    };
  }

  // ── Decimals and code lists ────────────────────────────────────────────────────

  private static void decimals(Invoice inv, List<Violation> out) {
    for (AllowanceCharge ac : inv.allowanceCharges()) {
      if (ac.charge()) {
        decimal(out, "BR-DEC-05", "Document level charge amount (BT-99)", ac.amount());
        decimal(out, "BR-DEC-06", "Document level charge base amount (BT-100)", ac.baseAmount());
      } else {
        decimal(out, "BR-DEC-01", "Document level allowance amount (BT-92)", ac.amount());
        decimal(out, "BR-DEC-02", "Document level allowance base amount (BT-93)", ac.baseAmount());
      }
    }
    Totals t = inv.totals();
    if (t != null) {
      decimal(out, "BR-DEC-09", "Sum of Invoice line net amount (BT-106)", t.lineNet());
      decimal(out, "BR-DEC-10", "Sum of allowances on document level (BT-107)", t.allowances());
      decimal(out, "BR-DEC-11", "Sum of charges on document level (BT-108)", t.charges());
      decimal(out, "BR-DEC-12", "Invoice total amount without VAT (BT-109)", t.withoutVat());
      decimal(out, "BR-DEC-13", "Invoice total VAT amount (BT-110)", t.vat());
      decimal(out, "BR-DEC-14", "Invoice total amount with VAT (BT-112)", t.withVat());
      decimal(
          out,
          "BR-DEC-15",
          "Invoice total VAT amount in accounting currency (BT-111)",
          t.vatInTaxCurrency());
      decimal(out, "BR-DEC-16", "Paid amount (BT-113)", t.paid());
      decimal(out, "BR-DEC-17", "Rounding amount (BT-114)", t.rounding());
      decimal(out, "BR-DEC-18", "Amount due for payment (BT-115)", t.payable());
    }
    for (VatBreakdown b : inv.vatBreakdown()) {
      decimal(out, "BR-DEC-19", "VAT category taxable amount (BT-116)", b.taxableAmount());
      decimal(out, "BR-DEC-20", "VAT category tax amount (BT-117)", b.taxAmount());
    }
    for (Line l : inv.lines()) {
      decimal(out, "BR-DEC-23", "Invoice line net amount (BT-131)", l.netAmount());
      for (AllowanceCharge ac : l.allowanceCharges()) {
        if (ac.charge()) {
          decimal(out, "BR-DEC-27", "Invoice line charge amount (BT-141)", ac.amount());
          decimal(out, "BR-DEC-28", "Invoice line charge base amount (BT-142)", ac.baseAmount());
        } else {
          decimal(out, "BR-DEC-24", "Invoice line allowance amount (BT-136)", ac.amount());
          decimal(out, "BR-DEC-25", "Invoice line allowance base amount (BT-137)", ac.baseAmount());
        }
      }
    }
  }

  private static void decimal(List<Violation> out, String rule, String what, BigDecimal value) {
    if (value != null && value.scale() > 2) {
      fail(
          out,
          rule,
          "The allowed maximum number of decimals for the "
              + what
              + " is 2: "
              + value.toPlainString()
              + ".");
    }
  }

  private static void codeLists(Invoice inv, List<Violation> out) {
    code(out, CodeList.DOCUMENT_TYPES, inv.typeCode(), "BR-CL-01", "document type code (BT-3)");
    code(out, CodeList.CURRENCIES, inv.currency(), "BR-CL-04", "Invoice currency code (BT-5)");
    code(
        out,
        CodeList.CURRENCIES,
        inv.taxCurrency(),
        "BR-CL-05",
        "VAT accounting currency code (BT-6)");
    code(
        out,
        CodeList.VAT_POINT_DATE_CODES,
        inv.taxPointDateCode(),
        "BR-CL-06",
        "Value added tax point date code (BT-8)");
    if (inv.invoicedObject() != null) {
      code(
          out,
          CodeList.OBJECT_SCHEMES,
          inv.invoicedObject().scheme(),
          "BR-CL-07",
          "invoiced object identifier scheme (BT-18)");
    }
    for (Party party : new Party[] {inv.seller(), inv.buyer()}) {
      if (party == null) continue;
      for (Identifier id : party.identifiers()) {
        code(out, CodeList.ICD, id.scheme(), "BR-CL-10", "identifier scheme");
      }
      if (party.legalRegistration() != null) {
        code(
            out,
            CodeList.ICD,
            party.legalRegistration().scheme(),
            "BR-CL-11",
            "legal registration identifier scheme");
      }
      if (party.electronicAddress() != null) {
        code(
            out,
            CodeList.EAS,
            party.electronicAddress().scheme(),
            "BR-CL-25",
            "electronic address scheme");
      }
      country(out, party.address(), "BR-CL-14");
    }
    Payee payee = inv.payee();
    if (payee != null) {
      if (payee.identifier() != null)
        code(
            out,
            CodeList.ICD,
            payee.identifier().scheme(),
            "BR-CL-10",
            "Payee identifier scheme (BT-60)");
      if (payee.legalRegistration() != null)
        code(
            out,
            CodeList.ICD,
            payee.legalRegistration().scheme(),
            "BR-CL-11",
            "Payee legal registration identifier scheme (BT-61)");
    }
    if (inv.taxRepresentative() != null)
      country(out, inv.taxRepresentative().address(), "BR-CL-14");
    if (inv.delivery() != null) {
      country(out, inv.delivery().address(), "BR-CL-14");
      if (inv.delivery().location() != null) {
        code(
            out,
            CodeList.ICD,
            inv.delivery().location().scheme(),
            "BR-CL-26",
            "Deliver to location identifier scheme (BT-71)");
      }
    }
    if (inv.payment() != null) {
      code(
          out,
          CodeList.PAYMENT_MEANS,
          inv.payment().meansCode(),
          "BR-CL-16",
          "Payment means type code (BT-81)");
    }
    for (VatBreakdown b : inv.vatBreakdown()) {
      code(out, CodeList.VAT_CATEGORIES, b.category(), "BR-CL-17", "VAT category code (BT-118)");
      code(
          out,
          CodeList.VATEX,
          b.exemptionReasonCode(),
          "BR-CL-22",
          "VAT exemption reason code (BT-121)");
    }
    for (AllowanceCharge ac : inv.allowanceCharges()) {
      code(
          out,
          CodeList.VAT_CATEGORIES,
          ac.vatCategory(),
          "BR-CL-18",
          "document level allowance or charge VAT category code");
      reasonCode(out, ac);
    }
    for (Line l : inv.lines()) {
      code(
          out,
          CodeList.VAT_CATEGORIES,
          l.vatCategory(),
          "BR-CL-18",
          "Invoiced item VAT category code (BT-151)");
      code(out, CodeList.UNITS, l.unitCode(), "BR-CL-23", "unit of measure code (BT-130)");
      if (l.objectId() != null) {
        code(
            out,
            CodeList.OBJECT_SCHEMES,
            l.objectId().scheme(),
            "BR-CL-07",
            "Invoice line object identifier scheme (BT-128)");
      }
      if (l.price() != null)
        code(
            out,
            CodeList.UNITS,
            l.price().baseQuantityUnit(),
            "BR-CL-23",
            "price base quantity unit (BT-150)");
      for (AllowanceCharge ac : l.allowanceCharges()) reasonCode(out, ac);
      Item item = l.item();
      if (item == null) continue;
      if (item.standardId() != null) {
        code(
            out,
            CodeList.ICD,
            item.standardId().scheme(),
            "BR-CL-21",
            "Item standard identifier scheme (BT-157)");
      }
      for (Classification c : item.classifications()) {
        code(
            out,
            CodeList.ITEM_CLASSIFICATION,
            c.scheme(),
            "BR-CL-13",
            "Item classification identifier scheme (BT-158)");
      }
      code(
          out,
          CodeList.COUNTRIES,
          item.originCountry(),
          "BR-CL-15",
          "Item country of origin (BT-159)");
    }
  }

  private static void reasonCode(List<Violation> out, AllowanceCharge ac) {
    if (ac.charge()) {
      code(out, CodeList.CHARGE_REASONS, ac.reasonCode(), "BR-CL-20", "charge reason code");
    } else {
      code(out, CodeList.ALLOWANCE_REASONS, ac.reasonCode(), "BR-CL-19", "allowance reason code");
    }
  }

  private static void country(List<Violation> out, Address a, String rule) {
    if (a != null) code(out, CodeList.COUNTRIES, a.country(), rule, "country code");
  }

  private static void code(
      List<Violation> out, CodeList list, String value, String rule, String what) {
    if (present(value) && !Codes.contains(list, value)) {
      fail(
          out,
          rule,
          "The "
              + what
              + " \""
              + value.strip()
              + "\" is not on the "
              + list.name()
              + " code list.");
    }
  }

  // ── Peppol BIS Billing 3.0 ────────────────────────────────────────────────────

  private static void peppol(Invoice inv, List<Violation> out) {
    if (!present(inv.profileId())) {
      fail(out, "PEPPOL-EN16931-R001", "Business process MUST be provided.");
    } else if (!PEPPOL_PROFILE.matcher(inv.profileId().strip()).matches()) {
      fail(
          out,
          "PEPPOL-EN16931-R007",
          "Business process MUST be in the format 'urn:fdc:peppol.eu:2017:poacc:billing:NN:1.0' where NN indicates the process number.");
    }
    String sellerCountry = countryOf(inv.seller());
    String buyerCountry = countryOf(inv.buyer());
    boolean bothGerman = "DE".equals(sellerCountry) && "DE".equals(buyerCountry);
    if (inv.notes().size() > 1 && !bothGerman) {
      fail(
          out,
          "PEPPOL-EN16931-R002",
          "No more than one note is allowed on document level, unless both the buyer and seller are German organizations.");
    }
    if (!present(inv.buyerReference()) && !present(inv.orderReference())) {
      fail(
          out,
          "PEPPOL-EN16931-R003",
          "A buyer reference or purchase order reference MUST be provided.");
    }
    if (inv.customizationId() == null
        || !inv.customizationId().strip().startsWith(Invoice.PEPPOL_BIS_3)) {
      fail(
          out,
          "PEPPOL-EN16931-R004",
          "Specification identifier MUST have the value '" + Invoice.PEPPOL_BIS_3 + "'.");
    }
    if (present(inv.taxCurrency()) && norm(inv.taxCurrency()).equals(norm(inv.currency()))) {
      fail(
          out,
          "PEPPOL-EN16931-R005",
          "VAT accounting currency code MUST be different from invoice currency code when provided.");
    }
    if (inv.buyer() == null || inv.buyer().electronicAddress() == null) {
      fail(out, "PEPPOL-EN16931-R010", "Buyer electronic address MUST be provided");
    }
    if (inv.seller() == null || inv.seller().electronicAddress() == null) {
      fail(out, "PEPPOL-EN16931-R020", "Seller electronic address MUST be provided");
    }
    Totals t = inv.totals();
    if (t != null
        && t.vat() != null
        && t.vatInTaxCurrency() != null
        && t.vat().signum() * t.vatInTaxCurrency().signum() < 0) {
      fail(
          out,
          "PEPPOL-EN16931-R055",
          "Invoice total VAT amount and Invoice total VAT amount in accounting currency MUST have the same operational sign");
    }
    PaymentInstructions payment = inv.payment();
    if (payment != null
        && DIRECT_DEBIT.contains(norm(payment.meansCode()))
        && (payment.directDebit() == null || !present(payment.directDebit().mandateReference()))) {
      fail(out, "PEPPOL-EN16931-R061", "Mandate reference MUST be provided for direct debit.");
    }
    for (AllowanceCharge ac : inv.allowanceCharges()) peppolAllowanceCharge(ac, out);
    for (Line l : inv.lines()) peppolLine(inv, l, out);

    String type = norm(inv.typeCode());
    if (type != null) {
      if (inv.isCreditNote()) {
        if (!Invoice.CREDIT_NOTE_TYPES.contains(type))
          fail(
              out,
              "PEPPOL-EN16931-P0101",
              "Credit note type code MUST be set according to the profile.");
      } else if (!PEPPOL_INVOICE_TYPES.contains(type)) {
        fail(
            out, "PEPPOL-EN16931-P0100", "Invoice type code MUST be set according to the profile.");
      }
      if (("326".equals(type) || "384".equals(type)) && !bothGerman) {
        fail(
            out,
            "PEPPOL-EN16931-P0112",
            "Invoice type code 326 or 384 are only allowed when both buyer and seller are German organizations");
      }
    }
    for (VatBreakdown b : inv.vatBreakdown()) {
      Exemption e =
          b.exemptionReasonCode() == null ? null : EXEMPTIONS.get(norm(b.exemptionReasonCode()));
      if (e != null && !e.category().equals(norm(b.category()))) {
        fail(
            out,
            e.rule(),
            "Tax Category "
                + e.category()
                + " MUST be used when exemption reason code is "
                + norm(b.exemptionReasonCode()));
      }
    }
    for (SupportingDocument d : inv.supportingDocuments()) {
      if (present(d.attachmentBase64()) && !Codes.contains(CodeList.PEPPOL_MIME, d.mimeCode())) {
        fail(
            out,
            "PEPPOL-EN16931-CL001",
            "Mime code must be according to subset of IANA code list.");
      }
    }
    for (Party party : new Party[] {inv.seller(), inv.buyer()}) {
      if (party == null) continue;
      Identifier endpoint = party.electronicAddress();
      if (endpoint != null
          && present(endpoint.scheme())
          && !Codes.contains(CodeList.PEPPOL_EAS, endpoint.scheme())) {
        fail(
            out,
            "PEPPOL-EN16931-CL008",
            "Electronic address identifier scheme must be from the codelist \"Electronic Address Identifier Scheme\"");
      }
      identifierFormat(out, endpoint);
      for (Identifier id : party.identifiers()) identifierFormat(out, id);
      identifierFormat(out, party.legalRegistration());
    }
    if (inv.payee() != null) {
      identifierFormat(out, inv.payee().identifier());
      identifierFormat(out, inv.payee().legalRegistration());
    }
  }

  private static void peppolAllowanceCharge(AllowanceCharge ac, List<Violation> out) {
    if (ac.percentage() != null && ac.baseAmount() == null) {
      fail(
          out,
          "PEPPOL-EN16931-R041",
          "Allowance/charge base amount MUST be provided when allowance/charge percentage is provided.");
    }
    if (ac.baseAmount() != null && ac.percentage() == null) {
      fail(
          out,
          "PEPPOL-EN16931-R042",
          "Allowance/charge percentage MUST be provided when allowance/charge base amount is provided.");
    }
    if (ac.baseAmount() != null && ac.percentage() != null) {
      BigDecimal expected = ac.baseAmount().multiply(ac.percentage()).divide(HUNDRED);
      if (!withinPeppolSlack(orZero(ac.amount()), expected)) {
        fail(
            out,
            "PEPPOL-EN16931-R040",
            "Allowance/charge amount must equal base amount * percentage/100 if base amount and percentage exists");
      }
    }
  }

  private static void peppolLine(Invoice inv, Line l, List<Violation> out) {
    for (AllowanceCharge ac : l.allowanceCharges()) peppolAllowanceCharge(ac, out);
    Price price = l.price();
    if (price != null) {
      if (price.gross() != null
          && price.net() != null
          && price.net().compareTo(price.gross().subtract(orZero(price.discount()))) != 0) {
        fail(
            out,
            "PEPPOL-EN16931-R046",
            "Item net price MUST equal (Gross price - Allowance amount) when gross price is provided (line "
                + l.id()
                + ").");
      }
      if (price.baseQuantity() != null && price.baseQuantity().signum() <= 0) {
        fail(
            out,
            "PEPPOL-EN16931-R121",
            "Base quantity MUST be a positive number above zero (line " + l.id() + ").");
      }
      if (present(price.baseQuantityUnit())
          && present(l.unitCode())
          && !norm(price.baseQuantityUnit()).equals(norm(l.unitCode()))) {
        fail(
            out,
            "PEPPOL-EN16931-R130",
            "Unit code of price base quantity MUST be same as invoiced quantity (line "
                + l.id()
                + ").");
      }
      if (l.quantity() != null && l.netAmount() != null && price.net() != null) {
        BigDecimal base =
            price.baseQuantity() == null || price.baseQuantity().signum() == 0
                ? BigDecimal.ONE
                : price.baseQuantity();
        BigDecimal expected =
            l.quantity().multiply(price.net().divide(base, MathContext.DECIMAL64));
        for (AllowanceCharge ac : l.allowanceCharges()) {
          expected =
              ac.charge()
                  ? expected.add(orZero(ac.amount()))
                  : expected.subtract(orZero(ac.amount()));
        }
        if (!withinPeppolSlack(l.netAmount(), expected)) {
          fail(
              out,
              "PEPPOL-EN16931-R120",
              "Invoice line net amount MUST equal (Invoiced quantity * (Item net price/item price base quantity) + Sum of invoice line charge amount - sum of invoice line allowance amount (line "
                  + l.id()
                  + ").");
        }
      }
    }
    Period invoicePeriod = inv.invoicingPeriod();
    Period linePeriod = l.period();
    if (invoicePeriod != null && linePeriod != null) {
      if (invoicePeriod.start() != null
          && linePeriod.start() != null
          && linePeriod.start().isBefore(invoicePeriod.start())) {
        fail(
            out,
            "PEPPOL-EN16931-R110",
            "Start date of line period MUST be within invoice period (line " + l.id() + ").");
      }
      if (invoicePeriod.end() != null
          && linePeriod.end() != null
          && linePeriod.end().isAfter(invoicePeriod.end())) {
        fail(
            out,
            "PEPPOL-EN16931-R111",
            "End date of line period MUST be within invoice period (line " + l.id() + ").");
      }
    }
  }

  /** PEPPOL-COMMON: the national identifiers whose format Peppol checks. */
  private static void identifierFormat(List<Violation> out, Identifier id) {
    if (id == null || !present(id.scheme()) || !present(id.id())) return;
    String v = id.id().strip();
    switch (id.scheme().strip()) {
      case "0088" -> {
        if (!gln(v))
          fail(
              out,
              "PEPPOL-COMMON-R040",
              "GLN must have a valid format according to GS1 rules: " + v);
      }
      case "0192" -> {
        if (!norwegianOrganisation(v))
          fail(
              out,
              "PEPPOL-COMMON-R041",
              "Norwegian organization number MUST be stated in the correct format: " + v);
      }
      case "0184" -> {
        if (!v.matches("DK[0-9]{8}|[0-9]{8}"))
          fail(
              out,
              "PEPPOL-COMMON-R042",
              "Danish organization number (CVR) MUST be stated in the correct format: " + v);
      }
      case "0208" -> {
        if (!belgianEnterprise(v))
          fail(
              out,
              "PEPPOL-COMMON-R043",
              "Belgian enterprise number MUST be stated in the correct format: " + v);
      }
      case "0007" -> {
        if (!swedishOrganisation(v))
          fail(
              out,
              "PEPPOL-COMMON-R049",
              "Swedish organization number MUST be stated in the correct format: " + v);
      }
      case "0151" -> {
        if (!australianBusinessNumber(v))
          fail(
              out,
              "PEPPOL-COMMON-R050",
              "Australian Business Number (ABN) MUST be stated in the correct format: " + v);
      }
      default -> {
        // no format rule for this scheme
      }
    }
  }

  /** GS1 mod 10: weights 3 and 1 from the rightmost digit before the check digit. */
  static boolean gln(String v) {
    if (!v.matches("[0-9]{2,}")) return false;
    int sum = 0;
    int position = 0;
    for (int i = v.length() - 2; i >= 0; i--) {
      sum += (v.charAt(i) - '0') * (position % 2 == 0 ? 3 : 1);
      position++;
    }
    return (10 - sum % 10) % 10 == v.charAt(v.length() - 1) - '0';
  }

  /** Norway's organisasjonsnummer: mod 11 with weights 3 2 7 6 5 4 3 2. */
  static boolean norwegianOrganisation(String v) {
    if (!v.matches("[0-9]{9}") || Long.parseLong(v) == 0) return false;
    int[] weights = {3, 2, 7, 6, 5, 4, 3, 2};
    int sum = 0;
    for (int i = 0; i < 8; i++) sum += (v.charAt(i) - '0') * weights[i];
    return (11 - sum % 11) % 11 == v.charAt(8) - '0';
  }

  /** Belgium's ondernemingsnummer: the last two digits are 97 less the first eight mod 97. */
  static boolean belgianEnterprise(String v) {
    if (!v.matches("[0-9]{10}")) return false;
    return Integer.parseInt(v.substring(8)) == 97 - Integer.parseInt(v.substring(0, 8)) % 97;
  }

  /** Sweden's organisationsnummer: Luhn over ten digits. */
  static boolean swedishOrganisation(String v) {
    if (!v.matches("[0-9]{10}")) return false;
    int sum = 0;
    for (int i = 0; i < 9; i++) {
      int d = v.charAt(i) - '0';
      if (i % 2 == 0) {
        d *= 2;
        if (d > 9) d -= 9;
      }
      sum += d;
    }
    return (10 - sum % 10) % 10 == v.charAt(9) - '0';
  }

  /** Australia's ABN: first digit less one, weights 10 1 3 5 … 19, mod 89. */
  static boolean australianBusinessNumber(String v) {
    if (!v.matches("[0-9]{11}")) return false;
    int[] weights = {10, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19};
    int sum = 0;
    for (int i = 0; i < 11; i++) {
      int d = v.charAt(i) - '0';
      if (i == 0) d -= 1;
      sum += d * weights[i];
    }
    return sum % 89 == 0;
  }

  // ── Arithmetic as the schematron does it ───────────────────────────────────────

  /** XPath's {@code round(x * 100) div 100}: half rounds towards positive infinity. */
  static BigDecimal round2(BigDecimal x) {
    return x.movePointRight(2).add(HALF).setScale(0, RoundingMode.FLOOR).movePointLeft(2);
  }

  private static BigDecimal roundInteger(BigDecimal x) {
    return x.add(HALF).setScale(0, RoundingMode.FLOOR);
  }

  /** {@code abs(a) - 1 < b and abs(a) + 1 > b}. */
  private static boolean withinOneUnit(BigDecimal a, BigDecimal b) {
    return a.subtract(BigDecimal.ONE).compareTo(b) < 0 && a.add(BigDecimal.ONE).compareTo(b) > 0;
  }

  private static boolean strictlyWithinOneUnit(BigDecimal taxable, BigDecimal sum) {
    return taxable.subtract(BigDecimal.ONE).compareTo(sum) < 0
        && taxable.add(BigDecimal.ONE).compareTo(sum) > 0;
  }

  private static boolean withinPeppolSlack(BigDecimal actual, BigDecimal expected) {
    return actual.subtract(expected).abs().compareTo(PEPPOL_SLACK) <= 0;
  }

  private static BigDecimal orZero(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static String countryOf(Party p) {
    return p == null || p.address() == null ? null : norm(p.address().country());
  }

  private static boolean present(String s) {
    return s != null && !s.isBlank();
  }

  private static String norm(String s) {
    return s == null ? null : s.strip().toUpperCase(Locale.ROOT);
  }

  private static void require(List<Violation> out, String value, String rule, String message) {
    if (!present(value)) fail(out, rule, message);
  }

  private static void fail(List<Violation> out, String rule, String message) {
    out.add(Violation.fatal(rule, message));
  }
}
