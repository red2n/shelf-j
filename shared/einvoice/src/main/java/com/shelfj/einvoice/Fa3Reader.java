package com.shelfj.einvoice;

import com.shelfj.einvoice.Invoice.Address;
import com.shelfj.einvoice.Invoice.Item;
import com.shelfj.einvoice.Invoice.Line;
import com.shelfj.einvoice.Invoice.Party;
import com.shelfj.einvoice.Invoice.PrecedingInvoice;
import com.shelfj.einvoice.Invoice.Price;
import com.shelfj.einvoice.Invoice.Totals;
import com.shelfj.einvoice.Invoice.VatBreakdown;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Poland's FA(3) into the EN 16931 model — the other direction from {@link Fa3}.
 *
 * <p>Needed because <b>KSeF hands invoices out in its own structure and takes no EN 16931 document
 * at all.</b> A Polish buyer does not receive invoices: it fetches them from the ministry's system,
 * and what comes back is FA(3). Reading it into the same model a UBL or CII document produces is
 * what lets the rest of the platform — the checks, the supplier matching, the three-way match, the
 * posting — work on a Polish invoice without knowing that it is one.
 *
 * <p>Three things do not survive the round trip, and saying so is better than pretending:
 *
 * <ul>
 *   <li>FA(3) has one address field where EN 16931 has a street, a city and a postcode. A Polish
 *       postcode is read out of the second line when it is written the way Poland writes it ({@code
 *       00-001 Warszawa}); anything else stays whole as the city, because splitting an address on a
 *       guess puts a street name in a postcode field.
 *   <li>A correction (KOR) carries its amounts negative. They are read back positive with the type
 *       code of a credit note, which is how the model and UBL both hold one.
 *   <li>A rate comes back from the bucket it was totalled into, not from a rate attribute: FA(3)
 *       states the rate as text ({@code 23}, {@code 0 KR}, {@code zw}, {@code oo}, {@code np I}),
 *       and the text is what decides the EN category.
 * </ul>
 */
final class Fa3Reader {

  private Fa3Reader() {}

  /** A Polish postcode and the city after it, as Poland writes them on one line. */
  private static final Pattern PL_POSTCODE = Pattern.compile("^(\\d{2}-\\d{3})\\s+(.+)$");

  /** The document is FA(3) when the root says so. */
  static boolean accepts(XmlElement root) {
    return Fa3.NAMESPACE.equals(root.namespace()) && "Faktura".equals(root.name());
  }

  static Invoice read(XmlElement root) {
    XmlElement fa = root.child("Fa");
    if (fa == null) {
      throw new EInvoiceFormatException(
          "NOT_AN_INVOICE", "the FA(3) document has no Fa element, so it carries no invoice");
    }
    boolean correction = "KOR".equals(text(fa.child("RodzajFaktury")));
    BigDecimal sign = correction ? BigDecimal.ONE.negate() : BigDecimal.ONE;

    List<Line> lines = new ArrayList<>();
    for (XmlElement row : fa.all("FaWiersz")) {
      lines.add(line(row, sign));
    }
    List<VatBreakdown> breakdown = breakdown(fa, sign);
    BigDecimal net = sum(breakdown.stream().map(VatBreakdown::taxableAmount).toList());
    BigDecimal vat = sum(breakdown.stream().map(VatBreakdown::taxAmount).toList());
    BigDecimal payable = amount(fa.child("P_15"), sign);

    LocalDate issueDate = date(fa.child("P_1"));
    LocalDate delivered = date(fa.child("P_6"));
    return new Invoice(
        null,
        null,
        text(fa.child("P_2")),
        issueDate,
        correction ? "381" : "380",
        text(fa.child("KodWaluty")),
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
        precedingInvoices(fa),
        party(root.child("Podmiot1")),
        party(root.child("Podmiot2")),
        null,
        null,
        delivered == null ? null : new Invoice.Delivery(null, null, delivered, null),
        null,
        null,
        List.of(),
        new Totals(
            sum(lines.stream().map(Line::netAmount).toList()),
            null,
            null,
            net,
            vat,
            null,
            net.add(vat),
            null,
            null,
            payable),
        breakdown,
        List.of(),
        lines);
  }

  /** One {@code FaWiersz}: the row number, the item, what was sold and at what rate. */
  private static Line line(XmlElement row, BigDecimal sign) {
    BigDecimal quantity = amount(row.child("P_8B"), sign);
    BigDecimal lineNet = amount(row.child("P_11"), sign);
    BigDecimal unitPrice = amount(row.child("P_9A"), BigDecimal.ONE);
    String p12 = text(row.child("P_12"));
    String[] categoryAndRate = category(p12);
    return new Line(
        text(row.child("NrWierszaFa")),
        null,
        null,
        quantity,
        text(row.child("P_8A")),
        lineNet,
        null,
        null,
        null,
        List.of(),
        unitPrice == null ? null : new Price(unitPrice, null, null, null, null),
        categoryAndRate[0],
        categoryAndRate[1] == null ? null : new BigDecimal(categoryAndRate[1]),
        new Item(text(row.child("P_7")), null, null, null, null, List.of(), null, List.of()));
  }

  /**
   * The rate buckets, read back into a VAT breakdown.
   *
   * <p>The bucket is the rate: FA(3) totals 23 per cent into {@code P_13_1}/{@code P_14_1} and
   * exempt supplies into {@code P_13_7} with no VAT bucket at all, so the element's own name is
   * what says which category and rate the money belongs to.
   */
  private static List<VatBreakdown> breakdown(XmlElement fa, BigDecimal sign) {
    List<VatBreakdown> out = new ArrayList<>();
    for (String[] bucket : BUCKETS) {
      XmlElement netElement = fa.child(bucket[0]);
      if (netElement == null) continue;
      BigDecimal net = amount(netElement, sign);
      BigDecimal vat = bucket[1] == null ? null : amount(fa.child(bucket[1]), sign);
      out.add(
          new VatBreakdown(
              net,
              vat == null ? BigDecimal.ZERO : vat,
              bucket[2],
              bucket[3] == null ? null : new BigDecimal(bucket[3]),
              "E".equals(bucket[2]) ? exemptionReason(fa) : null,
              null));
    }
    return out;
  }

  /** The net bucket, its VAT bucket, the EN category and the rate — {@link Fa3#bucket} inverted. */
  private static final String[][] BUCKETS = {
    {"P_13_1", "P_14_1", "S", "23"},
    {"P_13_2", "P_14_2", "S", "8"},
    {"P_13_3", "P_14_3", "S", "5"},
    {"P_13_4", "P_14_4", "S", "4"},
    {"P_13_5", "P_14_5", "S", "3"},
    {"P_13_6_1", null, "Z", "0"},
    {"P_13_7", null, "E", "0"},
    {"P_13_8", null, "O", "0"},
    {"P_13_9", null, "AE", "0"},
  };

  /** The reason an exempt supply gives, from the annotations. */
  private static String exemptionReason(XmlElement fa) {
    XmlElement annotations = fa.child("Adnotacje");
    XmlElement exemption = annotations == null ? null : annotations.child("Zwolnienie");
    return exemption == null ? null : text(exemption.child("P_19A"));
  }

  /** The EN category and rate a {@code P_12} text means. */
  private static String[] category(String p12) {
    if (p12 == null || p12.isBlank()) return new String[] {"S", null};
    String value = p12.strip();
    if ("zw".equals(value)) return new String[] {"E", "0"};
    if ("oo".equals(value)) return new String[] {"AE", "0"};
    if (value.startsWith("np")) return new String[] {"O", "0"};
    if (value.startsWith("0")) return new String[] {"Z", "0"};
    return new String[] {"S", value};
  }

  /** The invoice a correction corrects, when the document names one. */
  private static List<PrecedingInvoice> precedingInvoices(XmlElement fa) {
    XmlElement corrected = fa.child("DaneFaKorygowanej");
    if (corrected == null) return List.of();
    return List.of(
        new PrecedingInvoice(
            text(corrected.child("NrFaKorygowanej")),
            date(corrected.child("DataWystFaKorygowanej"))));
  }

  /** A party: who it is, by NIP or by an EU VAT number, and where it is. */
  private static Party party(XmlElement podmiot) {
    if (podmiot == null) return null;
    XmlElement id = podmiot.child("DaneIdentyfikacyjne");
    String nip = id == null ? null : text(id.child("NIP"));
    String euCountry = id == null ? null : text(id.child("KodUE"));
    String euNumber = id == null ? null : text(id.child("NrVatUE"));
    String vatId =
        nip != null
            ? "PL" + nip
            : euCountry != null && euNumber != null ? euCountry + euNumber : null;
    return new Party(
        id == null ? null : text(id.child("Nazwa")),
        null,
        List.of(),
        null,
        vatId,
        null,
        null,
        null,
        address(podmiot.child("Adres")),
        null);
  }

  /**
   * An address from FA(3)'s two lines.
   *
   * <p>The second line is {@code postcode city} where Poland writes it that way, and otherwise it
   * is left whole as the city: splitting on a guess would put a street name in a postcode field,
   * and a wrong postcode on a supplier is worse than none.
   */
  private static Address address(XmlElement adres) {
    if (adres == null) return null;
    String line2 = text(adres.child("AdresL2"));
    String postcode = null;
    String city = line2;
    if (line2 != null) {
      Matcher m = PL_POSTCODE.matcher(line2);
      if (m.matches()) {
        postcode = m.group(1);
        city = m.group(2);
      }
    }
    return new Address(
        text(adres.child("AdresL1")),
        null,
        null,
        city,
        postcode,
        null,
        text(adres.child("KodKraju")));
  }

  private static BigDecimal sum(List<BigDecimal> values) {
    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal v : values) if (v != null) total = total.add(v);
    return total;
  }

  private static BigDecimal amount(XmlElement element, BigDecimal sign) {
    String value = text(element);
    if (value == null) return null;
    try {
      return new BigDecimal(value).multiply(sign);
    } catch (NumberFormatException e) {
      throw new EInvoiceFormatException(
          "NOT_A_NUMBER", "FA(3) element " + element.name() + " is not a number: " + value, e);
    }
  }

  private static LocalDate date(XmlElement element) {
    String value = text(element);
    if (value == null) return null;
    try {
      return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
    } catch (RuntimeException e) {
      throw new EInvoiceFormatException(
          "NOT_A_DATE", "FA(3) element " + element.name() + " is not a date: " + value, e);
    }
  }

  private static String text(XmlElement element) {
    if (element == null) return null;
    String value = element.value();
    return value == null || value.isBlank() ? null : value.strip();
  }
}
