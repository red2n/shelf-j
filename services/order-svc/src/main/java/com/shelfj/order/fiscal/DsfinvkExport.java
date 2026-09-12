package com.shelfj.order.fiscal;

import com.shelfj.order.domain.Domain.FiscalReceipt;
import com.shelfj.order.domain.Domain.TseStamp;
import com.shelfj.order.repo.FiscalReceiptRepository.RegisterLine;
import com.shelfj.order.repo.FiscalReceiptRepository.RegisterOrder;
import com.shelfj.order.repo.FiscalReceiptRepository.RegisterTender;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The German inspector's file (18.5): DSFinV-K 2.3, the digital interface of the tax administration
 * for cash register systems, as a zip of the tables an audit reads first, with the GDPdU {@code
 * index.xml} that describes them.
 *
 * <p>Semicolon-separated, double-quote qualified, CRLF, UTF-8, decimal comma — the format's own
 * conventions, which is why the numbers here do not look like the numbers elsewhere. One cash-point
 * closing ({@code Z_NR}) per day the store traded: this register has no separate end-of-day
 * document, so the day is the closing.
 *
 * <p>Tables written: cashpointclosing, location, cashregister, tse, vat, businesscases, payment,
 * transactions, transactions_tse, transactions_vat, lines, lines_vat, datapayment. Not written: the
 * tables for agencies, vouchers, subitems, allocation groups and references, which this register
 * has nothing to put in.
 */
public final class DsfinvkExport {

  private DsfinvkExport() {}

  public static final String VERSION = "2.3";

  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /** One table: its file name and the columns in order, each typed for index.xml. */
  record Table(String name, List<Column> columns) {}

  /** A column's name and its GDPdU type: text, a number with this many decimals, or a datetime. */
  record Column(String name, String kind, int accuracy) {
    static Column text(String n) {
      return new Column(n, "text", 0);
    }

    static Column num(String n, int accuracy) {
      return new Column(n, "num", accuracy);
    }

    static Column date(String n) {
      return new Column(n, "date", 0);
    }
  }

  private static final List<Column> Z_KEY =
      List.of(Column.text("Z_KASSE_ID"), Column.date("Z_ERSTELLUNG"), Column.num("Z_NR", 0));

  private static List<Column> cols(Column... more) {
    List<Column> out = new ArrayList<>(Z_KEY);
    out.addAll(List.of(more));
    return out;
  }

  static final Map<String, List<Column>> TABLES = new LinkedHashMap<>();

  static {
    TABLES.put(
        "cashpointclosing",
        cols(
            Column.date("Z_BUCHUNGSTAG"),
            Column.text("TAXONOMIE_VERSION"),
            Column.text("Z_START_ID"),
            Column.text("Z_ENDE_ID"),
            Column.text("NAME"),
            Column.text("STRASSE"),
            Column.text("PLZ"),
            Column.text("ORT"),
            Column.text("LAND"),
            Column.text("STNR"),
            Column.text("USTID"),
            Column.num("Z_SE_ZAHLUNGEN", 2),
            Column.num("Z_SE_BARZAHLUNGEN", 2)));
    TABLES.put(
        "location",
        cols(
            Column.text("LOC_NAME"),
            Column.text("LOC_STRASSE"),
            Column.text("LOC_PLZ"),
            Column.text("LOC_ORT"),
            Column.text("LOC_LAND"),
            Column.text("LOC_USTID")));
    TABLES.put(
        "cashregister",
        cols(
            Column.text("KASSE_BRAND"),
            Column.text("KASSE_MODELL"),
            Column.text("KASSE_SERIENNR"),
            Column.text("KASSE_SW_BRAND"),
            Column.text("KASSE_SW_VERSION"),
            Column.text("KASSE_BASISWAEH_CODE"),
            Column.num("KEINE_UST_ZUORDNUNG", 0)));
    TABLES.put(
        "tse",
        cols(
            Column.num("TSE_ID", 0),
            Column.text("TSE_SERIAL"),
            Column.text("TSE_SIG_ALGO"),
            Column.text("TSE_ZEITFORMAT"),
            Column.text("TSE_PD_ENCODING"),
            Column.text("TSE_PUBLIC_KEY"),
            Column.text("TSE_ZERTIFIKAT_I"),
            Column.text("TSE_ZERTIFIKAT_II")));
    TABLES.put(
        "vat",
        cols(
            Column.num("UST_SCHLUESSEL", 0), Column.num("UST_SATZ", 2), Column.text("UST_BESCHR")));
    TABLES.put(
        "businesscases",
        cols(
            Column.text("GV_TYP"),
            Column.text("GV_NAME"),
            Column.text("AGENTUR_ID"),
            Column.num("UST_SCHLUESSEL", 0),
            Column.num("Z_UMS_BRUTTO", 2),
            Column.num("Z_UMS_NETTO", 2),
            Column.num("Z_UMS_UST", 2)));
    TABLES.put(
        "payment",
        cols(
            Column.text("ZAHLART_TYP"),
            Column.text("ZAHLART_NAME"),
            Column.num("Z_ZAHLART_BETRAG", 2)));
    TABLES.put(
        "transactions",
        cols(
            Column.text("BON_ID"),
            Column.num("BON_NR", 0),
            Column.text("BON_TYP"),
            Column.text("BON_NAME"),
            Column.text("TERMINAL_ID"),
            Column.num("BON_STORNO", 0),
            Column.date("BON_START"),
            Column.date("BON_ENDE"),
            Column.text("BEDIENER_ID"),
            Column.text("BEDIENER_NAME"),
            Column.num("UMS_BRUTTO", 2),
            Column.text("KUNDE_NAME"),
            Column.text("KUNDE_ID"),
            Column.text("KUNDE_TYP"),
            Column.text("KUNDE_STRASSE"),
            Column.text("KUNDE_PLZ"),
            Column.text("KUNDE_ORT"),
            Column.text("KUNDE_LAND"),
            Column.text("KUNDE_USTID"),
            Column.text("BON_NOTIZ")));
    TABLES.put(
        "transactions_tse",
        cols(
            Column.text("BON_ID"),
            Column.num("TSE_ID", 0),
            Column.num("TSE_TANR", 0),
            Column.date("TSE_TA_START"),
            Column.date("TSE_TA_ENDE"),
            Column.text("TSE_TA_VORGANGSART"),
            Column.num("TSE_TA_SIGZ", 0),
            Column.text("TSE_TA_SIG"),
            Column.text("TSE_TA_FEHLER"),
            Column.text("TSE_TA_VORGANGSDATEN")));
    TABLES.put(
        "transactions_vat",
        cols(
            Column.text("BON_ID"),
            Column.num("UST_SCHLUESSEL", 0),
            Column.num("BON_BRUTTO", 2),
            Column.num("BON_NETTO", 2),
            Column.num("BON_UST", 2)));
    TABLES.put(
        "lines",
        cols(
            Column.text("BON_ID"),
            Column.num("POS_ZEILE", 0),
            Column.text("GUTSCHEIN_NR"),
            Column.text("ARTIKELTEXT"),
            Column.text("POS_TERMINAL_ID"),
            Column.text("GV_TYP"),
            Column.text("GV_NAME"),
            Column.num("INHAUS", 0),
            Column.num("P_STORNO", 0),
            Column.text("AGENTUR_ID"),
            Column.text("ART_NR"),
            Column.text("GTIN"),
            Column.text("WARENGR_ID"),
            Column.text("WARENGR"),
            Column.num("MENGE", 3),
            Column.num("FAKTOR", 3),
            Column.text("EINHEIT"),
            Column.num("STK_BR", 5)));
    TABLES.put(
        "lines_vat",
        cols(
            Column.text("BON_ID"),
            Column.num("POS_ZEILE", 0),
            Column.num("UST_SCHLUESSEL", 0),
            Column.num("POS_BRUTTO", 5),
            Column.num("POS_NETTO", 5),
            Column.num("POS_UST", 5)));
    TABLES.put(
        "datapayment",
        cols(
            Column.text("BON_ID"),
            Column.text("ZAHLART_TYP"),
            Column.text("ZAHLART_NAME"),
            Column.text("ZAHLWAEH_CODE"),
            Column.num("ZAHLWAEH_BETRAG", 2),
            Column.num("BASISWAEH_BETRAG", 2)));
  }

  /** One day's closing: the documents issued that day, in number order. */
  record Closing(int number, LocalDate day, List<FiscalReceipt> docs) {
    Instant createdAt() {
      return docs.get(docs.size() - 1).issuedAt();
    }
  }

  /**
   * Writes the whole file.
   *
   * @param s the register
   * @return the zip's bytes
   */
  public static byte[] write(RegisterSnapshot s) {
    Map<String, StringBuilder> files = new LinkedHashMap<>();
    for (var t : TABLES.entrySet()) {
      StringBuilder sb = new StringBuilder();
      sb.append(String.join(";", t.getValue().stream().map(Column::name).toList())).append("\r\n");
      files.put(t.getKey(), sb);
    }
    String kasseId = s.device() != null ? s.device().clientId() : s.store().code();
    List<Closing> closings = closings(s.documents());
    BigDecimal[] rateTable = ProcessData.DE_RATE_POSITIONS.toArray(new BigDecimal[0]);

    for (Closing z : closings) {
      String[] zKey = {kasseId, dt(z.createdAt()), Integer.toString(z.number())};
      BigDecimal zPayments = BigDecimal.ZERO;
      BigDecimal zCash = BigDecimal.ZERO;
      BigDecimal[] zGross = zero5();
      BigDecimal[] zNet = zero5();
      BigDecimal[] zVat = zero5();
      Map<String, BigDecimal> zByMethod = new TreeMap<>();

      for (FiscalReceipt d : z.docs()) {
        RegisterOrder o = s.orders().get(d.number());
        boolean voided = d.voidedAt() != null;
        row(
            files.get("transactions"),
            zKey,
            d.fullNumber(),
            Long.toString(d.number()),
            "Beleg",
            "",
            kasseId,
            voided ? "1" : "0",
            dt(d.tse() != null && d.tse().startedAt() != null ? d.tse().startedAt() : d.issuedAt()),
            dt(
                d.tse() != null && d.tse().finishedAt() != null
                    ? d.tse().finishedAt()
                    : d.issuedAt()),
            d.issuedBy() == null ? "" : d.issuedBy().toString(),
            "",
            money(d.grossTotal()),
            "",
            o == null || o.customerId() == null ? "" : o.customerId().toString(),
            "",
            "",
            "",
            "",
            "",
            "",
            d.voidReason() == null ? "" : d.voidReason());

        TseStamp t = d.tse();
        row(
            files.get("transactions_tse"),
            zKey,
            d.fullNumber(),
            "1",
            t == null || t.transactionNumber() == null ? "" : Long.toString(t.transactionNumber()),
            t == null || t.startedAt() == null ? "" : dt(t.startedAt()),
            t == null || t.finishedAt() == null ? "" : dt(t.finishedAt()),
            t == null || t.processType() == null ? "" : t.processType(),
            t == null || t.signatureCounter() == null ? "" : Long.toString(t.signatureCounter()),
            t == null || t.signature() == null ? "" : t.signature(),
            t == null || t.error() == null ? "" : t.error(),
            t == null || t.processData() == null ? "" : t.processData());

        // Lines, and the document's totals by rate from them.
        BigDecimal[] docGross = zero5();
        BigDecimal[] docNet = zero5();
        BigDecimal[] docVat = zero5();
        int lineNo = 0;
        for (RegisterLine l : s.linesOf(d.number())) {
          lineNo++;
          BigDecimal vat = s.vatOf(l, d);
          BigDecimal net = l.lineTotal();
          BigDecimal gross = net.add(vat);
          int pos = ProcessData.position(s.ratePercentOf(l, d));
          docGross[pos - 1] = docGross[pos - 1].add(gross);
          docNet[pos - 1] = docNet[pos - 1].add(net);
          docVat[pos - 1] = docVat[pos - 1].add(vat);
          var product = s.productNames().get(l.variantId());
          BigDecimal unitGross =
              l.qty().signum() == 0
                  ? BigDecimal.ZERO
                  : gross.divide(l.qty(), 5, RoundingMode.HALF_UP);
          row(
              files.get("lines"),
              zKey,
              d.fullNumber(),
              Integer.toString(lineNo),
              "",
              s.nameOf(l),
              kasseId,
              "Umsatz",
              "",
              "1",
              voided ? "1" : "0",
              "",
              product == null || product.sku() == null ? l.variantId().toString() : product.sku(),
              "",
              "",
              "",
              num(l.qty(), 3),
              "1,000",
              product == null || product.unit() == null ? "Stk" : product.unit(),
              num(unitGross, 5));
          row(
              files.get("lines_vat"),
              zKey,
              d.fullNumber(),
              Integer.toString(lineNo),
              Integer.toString(pos),
              num(gross, 5),
              num(net, 5),
              num(vat, 5));
        }
        for (int i = 0; i < 5; i++) {
          if (docGross[i].signum() != 0 || docVat[i].signum() != 0) {
            row(
                files.get("transactions_vat"),
                zKey,
                d.fullNumber(),
                Integer.toString(i + 1),
                money(docGross[i]),
                money(docNet[i]),
                money(docVat[i]));
            zGross[i] = zGross[i].add(docGross[i]);
            zNet[i] = zNet[i].add(docNet[i]);
            zVat[i] = zVat[i].add(docVat[i]);
          }
        }

        List<RegisterTender> tenders = s.tendersOf(d.number());
        if (tenders.isEmpty()) {
          String method = o == null || o.paymentMethod() == null ? "" : o.paymentMethod();
          tenders = List.of(new RegisterTender(d.number(), method, d.grossTotal()));
        }
        for (RegisterTender p : tenders) {
          boolean cash = "CASH".equalsIgnoreCase(p.method());
          row(
              files.get("datapayment"),
              zKey,
              d.fullNumber(),
              cash ? "Bar" : "Unbar",
              p.method() == null ? "" : p.method(),
              d.currency(),
              money(p.amount()),
              money(p.amount()));
          zPayments = zPayments.add(p.amount());
          if (cash) {
            zCash = zCash.add(p.amount());
          }
          String name = (cash ? "Bar" : "Unbar") + ":" + (p.method() == null ? "" : p.method());
          zByMethod.merge(name, p.amount(), BigDecimal::add);
        }
      }

      String first = z.docs().get(0).fullNumber();
      String last = z.docs().get(z.docs().size() - 1).fullNumber();
      var biz = s.business();
      var st = s.store();
      row(
          files.get("cashpointclosing"),
          zKey,
          z.day().toString(),
          VERSION,
          first,
          last,
          biz.legalName() == null ? st.name() : biz.legalName(),
          join(st.line1(), st.line2()),
          nz(st.postalCode()),
          nz(st.city()),
          nz(st.country()),
          nz(s.settings().taxRegistrationNumber()),
          "",
          money(zPayments),
          money(zCash));
      row(
          files.get("location"),
          zKey,
          st.name(),
          join(st.line1(), st.line2()),
          nz(st.postalCode()),
          nz(st.city()),
          nz(st.country()),
          nz(s.settings().taxRegistrationNumber()));
      row(
          files.get("cashregister"),
          zKey,
          "Shelf-J",
          "POS",
          st.id().toString(),
          "Shelf-J",
          "1",
          s.currency(),
          "0");
      if (s.device() != null) {
        row(
            files.get("tse"),
            zKey,
            "1",
            s.device().serialNumber(),
            s.device().signatureAlgorithm(),
            s.device().timeFormat(),
            "UTF-8",
            s.device().publicKey(),
            "",
            "");
      }
      for (int i = 0; i < 5; i++) {
        row(
            files.get("vat"),
            zKey,
            Integer.toString(i + 1),
            num(rateTable[i], 2),
            ProcessData.DE_VAT_KEY_NAMES.get(i + 1));
        if (zGross[i].signum() != 0 || zVat[i].signum() != 0) {
          row(
              files.get("businesscases"),
              zKey,
              "Umsatz",
              "",
              "",
              Integer.toString(i + 1),
              money(zGross[i]),
              money(zNet[i]),
              money(zVat[i]));
        }
      }
      for (var e : zByMethod.entrySet()) {
        String[] parts = e.getKey().split(":", 2);
        row(
            files.get("payment"),
            zKey,
            parts[0],
            parts.length > 1 ? parts[1] : "",
            money(e.getValue()));
      }
    }

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      put(zip, "index.xml", indexXml(s, closings));
      put(zip, "gdpdu-01-09-2004.dtd", DTD);
      for (var f : files.entrySet()) {
        put(zip, f.getKey() + ".csv", f.getValue().toString());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  /** The documents grouped by the UTC day they were issued, numbered in day order. */
  static List<Closing> closings(List<FiscalReceipt> docs) {
    Map<LocalDate, List<FiscalReceipt>> byDay = new TreeMap<>();
    for (FiscalReceipt d : docs) {
      byDay
          .computeIfAbsent(
              d.issuedAt().atZone(ZoneOffset.UTC).toLocalDate(), k -> new ArrayList<>())
          .add(d);
    }
    List<Closing> out = new ArrayList<>();
    int n = 0;
    for (var e : byDay.entrySet()) {
      out.add(new Closing(++n, e.getKey(), e.getValue()));
    }
    return out;
  }

  private static void put(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static void row(StringBuilder sb, String[] zKey, String... cells) {
    for (int i = 0; i < zKey.length; i++) {
      if (i > 0) {
        sb.append(';');
      }
      sb.append(cell(zKey[i]));
    }
    for (String c : cells) {
      sb.append(';').append(cell(c));
    }
    sb.append("\r\n");
  }

  /** Text is quoted when it carries the delimiter, a quote or a line break; quotes are doubled. */
  static String cell(String v) {
    if (v == null) {
      return "";
    }
    if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
      return "\"" + v.replace("\"", "\"\"") + "\"";
    }
    return v;
  }

  /** A number with a decimal comma — DSFinV-K's own convention. */
  static String num(BigDecimal v, int scale) {
    return v.setScale(scale, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
  }

  static String money(BigDecimal v) {
    return num(v, 2);
  }

  static String dt(Instant i) {
    return ISO_MILLIS.format(i);
  }

  private static BigDecimal[] zero5() {
    BigDecimal[] a = new BigDecimal[5];
    java.util.Arrays.fill(a, BigDecimal.ZERO);
    return a;
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  private static String join(String a, String b) {
    if (a == null || a.isBlank()) {
      return nz(b);
    }
    return b == null || b.isBlank() ? a : a + ", " + b;
  }

  static String indexXml(RegisterSnapshot s, List<Closing> closings) {
    StringBuilder x = new StringBuilder();
    x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    x.append("<!DOCTYPE DataSet SYSTEM \"gdpdu-01-09-2004.dtd\">\n");
    x.append("<DataSet>\n <Version>").append(VERSION).append("</Version>\n");
    x.append(" <DataSupplier>\n  <Name>")
        .append(esc(nz(s.business().legalName())))
        .append("</Name>\n");
    x.append("  <Location>").append(esc(nz(s.store().city()))).append("</Location>\n");
    x.append("  <Comment>Shelf-J DSFinV-K ")
        .append(VERSION)
        .append(" export, series ")
        .append(esc(s.seriesCode()))
        .append(" period ")
        .append(esc(s.period()))
        .append(", generated ")
        .append(s.generatedAt())
        .append("</Comment>\n </DataSupplier>\n");
    x.append(" <Media>\n  <Name>Shelf-J DSFinV-K</Name>\n");
    String from = closings.isEmpty() ? s.period() + "-01-01" : closings.get(0).day().toString();
    String to =
        closings.isEmpty()
            ? s.period() + "-12-31"
            : closings.get(closings.size() - 1).day().toString();
    for (var t : TABLES.entrySet()) {
      x.append("  <Table>\n   <URL>")
          .append(t.getKey())
          .append(".csv</URL>\n   <Name>")
          .append(t.getKey())
          .append("</Name>\n   <Description>DSFinV-K ")
          .append(t.getKey())
          .append("</Description>\n");
      x.append("   <Validity><Range><From>")
          .append(from)
          .append("</From><To>")
          .append(to)
          .append("</To></Range></Validity>\n");
      x.append("   <UTF8/>\n   <DecimalSymbol>,</DecimalSymbol>\n");
      x.append("   <DigitGroupingSymbol>.</DigitGroupingSymbol>\n");
      x.append("   <Range><From>2</From></Range>\n");
      x.append("   <VariableLength>\n    <ColumnDelimiter>;</ColumnDelimiter>\n");
      x.append("    <RecordDelimiter>&#13;&#10;</RecordDelimiter>\n");
      x.append("    <TextEncapsulator>\"</TextEncapsulator>\n");
      boolean first = true;
      for (Column c : t.getValue()) {
        String tag = first ? "VariablePrimaryKey" : "VariableColumn";
        first = false;
        x.append("    <").append(tag).append(">\n     <Name>").append(c.name()).append("</Name>\n");
        switch (c.kind()) {
          case "num" ->
              x.append("     <Numeric><Accuracy>")
                  .append(c.accuracy())
                  .append("</Accuracy></Numeric>\n");
          case "date" -> x.append("     <Date><Format>YYYY-MM-DDThh:mm:ss.fffZ</Format></Date>\n");
          default -> x.append("     <AlphaNumeric/>\n");
        }
        x.append("    </").append(tag).append(">\n");
      }
      x.append("   </VariableLength>\n  </Table>\n");
    }
    x.append(" </Media>\n</DataSet>\n");
    return x.toString();
  }

  private static String esc(String v) {
    return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** The GDPdU document type the index refers to — the elements this index uses. */
  static final String DTD =
      """
      <!ELEMENT DataSet (Version, DataSupplier?, Media+)>
      <!ELEMENT Version (#PCDATA)>
      <!ELEMENT DataSupplier (Name, Location, Comment?)>
      <!ELEMENT Name (#PCDATA)>
      <!ELEMENT Location (#PCDATA)>
      <!ELEMENT Comment (#PCDATA)>
      <!ELEMENT Media (Name, Table*)>
      <!ELEMENT Table (URL, Name, Description?, Validity?, UTF8?, DecimalSymbol?, DigitGroupingSymbol?, Range?, VariableLength)>
      <!ELEMENT URL (#PCDATA)>
      <!ELEMENT Description (#PCDATA)>
      <!ELEMENT Validity (Range)>
      <!ELEMENT Range (From, To?)>
      <!ELEMENT From (#PCDATA)>
      <!ELEMENT To (#PCDATA)>
      <!ELEMENT UTF8 EMPTY>
      <!ELEMENT DecimalSymbol (#PCDATA)>
      <!ELEMENT DigitGroupingSymbol (#PCDATA)>
      <!ELEMENT VariableLength (ColumnDelimiter, RecordDelimiter, TextEncapsulator, VariablePrimaryKey+, VariableColumn*)>
      <!ELEMENT ColumnDelimiter (#PCDATA)>
      <!ELEMENT RecordDelimiter (#PCDATA)>
      <!ELEMENT TextEncapsulator (#PCDATA)>
      <!ELEMENT VariablePrimaryKey (Name, (AlphaNumeric | Numeric | Date))>
      <!ELEMENT VariableColumn (Name, (AlphaNumeric | Numeric | Date))>
      <!ELEMENT AlphaNumeric EMPTY>
      <!ELEMENT Numeric (Accuracy?)>
      <!ELEMENT Accuracy (#PCDATA)>
      <!ELEMENT Date (Format?)>
      <!ELEMENT Format (#PCDATA)>
      """;
}
