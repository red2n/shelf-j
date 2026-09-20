package com.storeql.order.domain;

import com.storeql.einvoice.Invoice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The EN 16931 invoice or credit note a sale to a business owes, built from what the sale recorded
 * (18.9): each line net of its own discounts and taxed at the rate its quote applied, the order's
 * own discount as an allowance on the rate it came off, and the business and its buyer as they name
 * themselves for VAT.
 *
 * <p>Pure: the parties, the lines and the numbers are given, so the arithmetic and the categories
 * can be held to CEN's and Peppol's rules without a database or another service.
 */
public final class SalesInvoiceDraft {

  /** UNTDID 1001 invoice. */
  public static final String TYPE_INVOICE = "380";

  /** UNTDID 1001 credit note. */
  public static final String TYPE_CREDIT_NOTE = "381";

  /** UNTDID 5189 discount. */
  static final String DISCOUNT_REASON_CODE = "95";

  private SalesInvoiceDraft() {}

  /** A postal address, as EN 16931 carries it (BG-5, BG-8). */
  public record Address(
      String line1,
      String line2,
      String city,
      String postcode,
      String subdivision,
      String country) {}

  /** The business issuing the document (BG-4). */
  public record Seller(
      String legalName,
      String tradingName,
      String vatId,
      String einvoiceScheme,
      String einvoiceId,
      Address address) {}

  /**
   * The business buying (BG-7).
   *
   * @param reverseCharge the buyer accounts for the VAT itself, so a zero-rated line is category AE
   */
  public record Buyer(
      String name,
      String vatId,
      String einvoiceScheme,
      String einvoiceId,
      Address address,
      boolean reverseCharge) {}

  /**
   * One line as the sale recorded it.
   *
   * @param netAmount the line's value after its own discounts, before the order's
   * @param vatRate the rate in percent: 20 for 20%
   * @param exemptionReason why a line is exempt from VAT; null for a taxed or zero-rated line
   */
  public record Line(
      String name,
      String sellersItemId,
      String hsnCode,
      BigDecimal quantity,
      String unitCode,
      BigDecimal netAmount,
      BigDecimal vatRate,
      String exemptionReason) {}

  /**
   * The document's own facts.
   *
   * @param discount the order's discount off the lines, net of VAT; zero for none
   * @param payable what the buyer owes on the document, when the sale fixed it; null to compute it
   * @param spreadDiscounts put the order's discount into the lines rather than beside them, as
   *     India's INV-01 requires
   */
  public record Document(
      String typeCode,
      String number,
      LocalDate issueDate,
      String currency,
      String orderReference,
      String precedingNumber,
      LocalDate precedingDate,
      String paymentTerms,
      BigDecimal discount,
      BigDecimal payable,
      boolean spreadDiscounts) {}

  /**
   * The invoice or credit note.
   *
   * @throws IllegalArgumentException when there are no lines, or a line has no quantity or amount
   */
  public static Invoice build(Document d, Seller seller, Buyer buyer, List<Line> given) {
    if (given.isEmpty()) {
      throw new IllegalArgumentException("a document needs at least one line");
    }
    for (Line l : given) {
      if (l.quantity() == null || l.quantity().signum() <= 0 || l.netAmount() == null) {
        throw new IllegalArgumentException("every line needs a quantity and an amount");
      }
    }
    BigDecimal discount =
        d.discount() == null ? BigDecimal.ZERO : money(d.discount().max(BigDecimal.ZERO));
    List<Line> lines = d.spreadDiscounts() ? spread(given, discount) : given;
    BigDecimal documentDiscount = d.spreadDiscounts() ? BigDecimal.ZERO : discount;

    // Lines grouped by what they are taxed as: the breakdown and the allowances follow the groups.
    Map<String, Group> groups = new LinkedHashMap<>();
    List<Invoice.Line> modelLines = new ArrayList<>();
    BigDecimal lineNet = BigDecimal.ZERO;
    for (int i = 0; i < lines.size(); i++) {
      Line l = lines.get(i);
      String category = category(l, buyer);
      BigDecimal rate = "S".equals(category) ? l.vatRate() : BigDecimal.ZERO;
      BigDecimal net = money(l.netAmount());
      lineNet = lineNet.add(net);
      groups
          .computeIfAbsent(
              category + "|" + rate.stripTrailingZeros().toPlainString(),
              k -> new Group(category, rate, reasonFor(category, l)))
          .add(net);
      modelLines.add(
          new Invoice.Line(
              Integer.toString(i + 1),
              null,
              null,
              l.quantity(),
              unitCode(l.unitCode()),
              net,
              null,
              null,
              null,
              List.of(),
              new Invoice.Price(unitPrice(net, l.quantity()), null, null, null, null),
              category,
              rate,
              new Invoice.Item(
                  l.name() == null || l.name().isBlank() ? "Item " + (i + 1) : l.name(),
                  null,
                  l.sellersItemId(),
                  null,
                  null,
                  l.hsnCode() == null
                      ? List.of()
                      : List.of(new Invoice.Classification(l.hsnCode(), "HS", null)),
                  null,
                  List.of())));
    }

    // The order's discount, shared over the groups by value; the largest group takes the remainder.
    List<Invoice.AllowanceCharge> allowances = new ArrayList<>();
    if (documentDiscount.signum() > 0 && lineNet.signum() > 0) {
      String largest =
          groups.entrySet().stream()
              .max((a, b) -> a.getValue().net.compareTo(b.getValue().net))
              .orElseThrow()
              .getKey();
      BigDecimal shared = BigDecimal.ZERO;
      for (Map.Entry<String, Group> e : groups.entrySet()) {
        if (e.getKey().equals(largest)) continue;
        Group g = e.getValue();
        g.allowance =
            money(documentDiscount.multiply(g.net).divide(lineNet, 6, RoundingMode.HALF_UP));
        shared = shared.add(g.allowance);
      }
      groups.get(largest).allowance = documentDiscount.subtract(shared);
      for (Group g : groups.values()) {
        if (g.allowance.signum() == 0) continue;
        allowances.add(
            new Invoice.AllowanceCharge(
                false,
                g.allowance,
                null,
                null,
                g.category,
                g.rate,
                "Discount",
                DISCOUNT_REASON_CODE));
      }
    }

    List<Invoice.VatBreakdown> breakdown = new ArrayList<>();
    BigDecimal vat = BigDecimal.ZERO;
    for (Group g : groups.values()) {
      BigDecimal taxable = g.net.subtract(g.allowance);
      BigDecimal tax = money(taxable.multiply(g.rate).divide(BigDecimal.valueOf(100)));
      vat = vat.add(tax);
      breakdown.add(
          new Invoice.VatBreakdown(
              taxable,
              tax,
              g.category,
              g.rate,
              g.reason,
              "AE".equals(g.category) ? "VATEX-EU-AE" : null));
    }
    BigDecimal withoutVat = lineNet.subtract(documentDiscount);
    BigDecimal withVat = withoutVat.add(vat);
    // What the sale charged can differ from the breakdown by the pennies its own rounding took;
    // up to a unit is carried as rounding (BT-114), anything more is not this document's to
    // explain.
    BigDecimal rounding = null;
    BigDecimal payable = withVat;
    if (d.payable() != null) {
      BigDecimal diff = money(d.payable()).subtract(withVat);
      if (diff.signum() != 0 && diff.abs().compareTo(BigDecimal.ONE) < 0) {
        rounding = diff;
        payable = money(d.payable());
      }
    }
    Invoice.Totals totals =
        new Invoice.Totals(
            lineNet,
            allowances.isEmpty() ? null : documentDiscount,
            null,
            withoutVat,
            vat,
            null,
            withVat,
            null,
            rounding,
            payable);

    boolean peppol =
        present(seller.einvoiceId()) && present(buyer.einvoiceId()) && present(d.orderReference());
    return new Invoice(
        peppol ? Invoice.PEPPOL_BIS_3 : Invoice.EN16931,
        peppol ? Invoice.PEPPOL_BILLING_PROFILE : null,
        d.number(),
        d.issueDate(),
        d.typeCode(),
        d.currency(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        d.orderReference(),
        null,
        null,
        null,
        null,
        null,
        null,
        d.paymentTerms(),
        List.of(),
        d.precedingNumber() == null
            ? List.of()
            : List.of(new Invoice.PrecedingInvoice(d.precedingNumber(), d.precedingDate())),
        party(
            seller.legalName(),
            seller.tradingName(),
            seller.vatId(),
            seller.einvoiceScheme(),
            seller.einvoiceId(),
            seller.address()),
        party(
            buyer.name(),
            null,
            buyer.vatId(),
            buyer.einvoiceScheme(),
            buyer.einvoiceId(),
            buyer.address()),
        null,
        null,
        null,
        null,
        null,
        allowances,
        totals,
        breakdown,
        List.of(),
        modelLines);
  }

  /**
   * What the order's discount comes to before VAT.
   *
   * <p>A till takes its discount off what the customer pays, VAT included, after the tax was worked
   * out on the full lines; a basket promotion comes off the same way. The invoice states the VAT on
   * what was actually received, so the discount it carries is the net one that leaves the lines
   * plus their VAT at what the sale charged.
   *
   * @param lines the lines at their full value, with the rates they were taxed at
   * @param payable what the sale charged, VAT included
   * @return the net discount, to the penny, never negative; zero when the lines were paid in full
   */
  public static BigDecimal netDiscount(List<Line> lines, BigDecimal payable) {
    if (payable == null) return money(BigDecimal.ZERO);
    BigDecimal net = BigDecimal.ZERO;
    BigDecimal gross = BigDecimal.ZERO;
    for (Line l : lines) {
      BigDecimal amount = money(l.netAmount());
      BigDecimal rate = l.vatRate() == null ? BigDecimal.ZERO : l.vatRate();
      net = net.add(amount);
      gross = gross.add(amount.multiply(BigDecimal.ONE.add(rate.movePointLeft(2))));
    }
    if (gross.signum() <= 0) return money(BigDecimal.ZERO);
    BigDecimal kept = net.multiply(payable).divide(gross, 6, RoundingMode.HALF_UP);
    BigDecimal discount = net.subtract(kept);
    return money(discount.signum() > 0 ? discount : BigDecimal.ZERO);
  }

  /** What a line is taxed as: standard, zero-rated, exempt, or reverse charge. */
  static String category(Line l, Buyer buyer) {
    if (l.vatRate() != null && l.vatRate().signum() > 0) return "S";
    if (l.exemptionReason() != null && !l.exemptionReason().isBlank()) return "E";
    return buyer.reverseCharge() ? "AE" : "Z";
  }

  private static String reasonFor(String category, Line l) {
    return switch (category) {
      case "E" -> l.exemptionReason();
      case "AE" -> "Reverse charge";
      default -> null;
    };
  }

  /** The order's discount taken off the lines by value, the last line taking the remainder. */
  static List<Line> spread(List<Line> lines, BigDecimal discount) {
    if (discount.signum() == 0) return lines;
    BigDecimal total =
        lines.stream().map(l -> money(l.netAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.signum() <= 0) return lines;
    List<Line> out = new ArrayList<>();
    BigDecimal taken = BigDecimal.ZERO;
    for (int i = 0; i < lines.size(); i++) {
      Line l = lines.get(i);
      BigDecimal share =
          i == lines.size() - 1
              ? discount.subtract(taken)
              : money(
                  discount.multiply(money(l.netAmount())).divide(total, 6, RoundingMode.HALF_UP));
      taken = taken.add(share);
      out.add(
          new Line(
              l.name(),
              l.sellersItemId(),
              l.hsnCode(),
              l.quantity(),
              l.unitCode(),
              money(l.netAmount()).subtract(share),
              l.vatRate(),
              l.exemptionReason()));
    }
    return out;
  }

  /** The unit of measure a product is sold in, as UN/ECE Recommendation 20 names it. */
  static String unitCode(String unit) {
    if (unit == null) return "C62";
    return switch (unit.strip().toUpperCase(Locale.ROOT)) {
      case "KG", "KGM" -> "KGM";
      case "G", "GRM" -> "GRM";
      case "L", "LTR" -> "LTR";
      case "ML", "MLT" -> "MLT";
      case "M", "MTR" -> "MTR";
      case "CM", "CMT" -> "CMT";
      case "SQM", "M2", "MTK" -> "MTK";
      case "DOZEN", "DZ", "DZN" -> "DZN";
      case "BOX", "XBX" -> "XBX";
      case "PACK", "XPK" -> "XPK";
      default -> "C62";
    };
  }

  private static BigDecimal unitPrice(BigDecimal net, BigDecimal quantity) {
    BigDecimal price = net.divide(quantity, 6, RoundingMode.HALF_UP).stripTrailingZeros();
    return price.scale() < 0 ? price.setScale(0) : price;
  }

  private static Invoice.Party party(
      String name, String tradingName, String vatId, String scheme, String id, Address a) {
    return new Invoice.Party(
        name,
        tradingName,
        List.of(),
        null,
        vatId,
        null,
        null,
        present(scheme) && present(id) ? new Invoice.Identifier(id, scheme) : null,
        a == null
            ? null
            : new Invoice.Address(
                a.line1(), a.line2(), null, a.city(), a.postcode(), a.subdivision(), a.country()),
        null);
  }

  private static boolean present(String s) {
    return s != null && !s.isBlank();
  }

  private static BigDecimal money(BigDecimal x) {
    return x.setScale(2, RoundingMode.HALF_UP);
  }

  /** The lines taxed alike, what they come to, and the discount they take. */
  private static final class Group {
    final String category;
    final BigDecimal rate;
    final String reason;
    BigDecimal net = BigDecimal.ZERO;
    BigDecimal allowance = BigDecimal.ZERO;

    Group(String category, BigDecimal rate, String reason) {
      this.category = category;
      this.rate = rate;
      this.reason = reason;
    }

    void add(BigDecimal amount) {
      net = net.add(amount);
    }
  }
}
