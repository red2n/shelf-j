package com.shelfj.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Bank-standard payment files (17.12): pain.001 for euro runs, Bacs Standard 18 for sterling, and
 * the bank's pain.002 answer read back — each checked against what the published layouts say, and
 * against the files and reports a bank would refuse.
 */
class BankFilesTest {

  private static final String DE_IBAN = "DE89370400440532013000";
  private static final String FR_IBAN = "FR1420041010050500013M02606";
  private static final Pain001.Account PAYER =
      new Pain001.Account("Corner Shop BV", "NL91 ABNA 0417 1643 00", "ABNANL2A");

  private static Pain001.Initiation initiation(List<Pain001.Transfer> transfers) {
    return new Pain001.Initiation(
        "PAY260915-3F9A1C",
        Instant.parse("2026-09-15T10:11:12.345Z"),
        LocalDate.of(2026, 9, 18),
        PAYER,
        transfers);
  }

  private static Pain001.Transfer transfer(String e2e, String amount, String name, String iban) {
    return new Pain001.Transfer(
        e2e, new BigDecimal(amount), new Pain001.Account(name, iban, null), "PAY260915-3F9A1C");
  }

  private static Document parse(String xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return f.newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private static String first(Document d, String tag) {
    return d.getElementsByTagNameNS(Pain001.NAMESPACE, tag).item(0).getTextContent();
  }

  // ── pain.001 ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A euro run is one SEPA payment block, a transfer per supplier, totals that add up")
  void aEuroRunIsASepaInitiation() throws Exception {
    String xml =
        Pain001.write(
            initiation(
                List.of(
                    transfer("E2E-1", "120.5", "Muster GmbH", "DE89 3704 0044 0532 0130 00"),
                    new Pain001.Transfer(
                        "E2E-2",
                        new BigDecimal("30.00"),
                        new Pain001.Account("Dupont SA", FR_IBAN, "BNPAFRPP"),
                        "PAY260915-3F9A1C"))));
    Document d = parse(xml);
    assertThat(d.getDocumentElement().getNamespaceURI(), is(Pain001.NAMESPACE));
    assertThat(first(d, "MsgId"), is("PAY260915-3F9A1C"));
    assertThat(first(d, "CreDtTm"), is("2026-09-15T10:11:12Z"));
    assertThat(first(d, "NbOfTxs"), is("2"));
    assertThat(first(d, "CtrlSum"), is("150.50"));
    assertThat(first(d, "PmtMtd"), is("TRF"));
    assertThat(first(d, "Cd"), is("SEPA"));
    assertThat(first(d, "ChrgBr"), is("SLEV"));
    assertThat(first(d, "Dt"), is("2026-09-18"));
    assertThat(first(d, "IBAN"), is("NL91ABNA0417164300"));
    Element amount = (Element) d.getElementsByTagNameNS(Pain001.NAMESPACE, "InstdAmt").item(0);
    assertThat(amount.getAttribute("Ccy"), is("EUR"));
    assertThat(amount.getTextContent(), is("120.50"));
    assertThat(
        d.getElementsByTagNameNS(Pain001.NAMESPACE, "EndToEndId").item(1).getTextContent(),
        is("E2E-2"));
    assertThat(xml, containsString("<IBAN>" + DE_IBAN + "</IBAN>"));
    // A creditor's bank is named only when its BIC is known.
    assertThat(d.getElementsByTagNameNS(Pain001.NAMESPACE, "CdtrAgt").getLength(), is(1));
    assertThat(first(d, "Ustrd"), is("PAY260915-3F9A1C"));
  }

  @Test
  @DisplayName("Names are reduced to the SEPA Latin set; markup in a name is text, never XML")
  void namesAreLatinAndMarkupIsText() throws Exception {
    String xml =
        Pain001.write(
            initiation(
                List.of(
                    transfer("E2E-1", "10", "Müller & Söhne <b>Ltd</b> \"Ø\"", DE_IBAN),
                    transfer("E2E-2", "10", "A".repeat(90), FR_IBAN))));
    Document d = parse(xml);
    assertThat(
        d.getElementsByTagNameNS(Pain001.NAMESPACE, "Nm").item(2).getTextContent(),
        is("Muller + Sohne b Ltd /b"));
    assertThat(
        d.getElementsByTagNameNS(Pain001.NAMESPACE, "Nm").item(3).getTextContent().length(),
        is(70));
    assertThat(xml, not(containsString("<b>")));
    // The paying account without a BIC is written NOTPROVIDED, as the guidelines allow.
    String noBic =
        Pain001.write(
            new Pain001.Initiation(
                "PAY-1",
                Instant.now(),
                LocalDate.of(2026, 9, 18),
                new Pain001.Account("Corner Shop", "NL91ABNA0417164300", null),
                List.of(transfer("E2E-1", "10", "Muster", DE_IBAN))));
    assertThat(
        noBic,
        containsString(
            "<DbtrAgt><FinInstnId><Othr><Id>NOTPROVIDED</Id></Othr></FinInstnId></DbtrAgt>"));
  }

  @Test
  @DisplayName("What SEPA would refuse is refused before a file exists")
  void sepaRefusals() {
    List<List<Pain001.Transfer>> bad =
        List.of(
            List.of(),
            List.of(transfer("E2E-1", "0", "Muster", DE_IBAN)),
            List.of(transfer("E2E-1", "-5", "Muster", DE_IBAN)),
            List.of(transfer("E2E-1", "1.005", "Muster", DE_IBAN)),
            List.of(transfer("E2E-1", "1000000000.00", "Muster", DE_IBAN)),
            List.of(transfer("E2E-1", "10", "Muster", "DE89370400440532013001")),
            List.of(transfer("E2E-1", "10", "Muster", "31415926")),
            List.of(transfer("E2E-1", "10", "*** ###", DE_IBAN)),
            List.of(transfer("/E2E-1", "10", "Muster", DE_IBAN)),
            List.of(transfer("E2E//1", "10", "Muster", DE_IBAN)),
            List.of(transfer("E2E-ü", "10", "Muster", DE_IBAN)),
            List.of(transfer("E".repeat(36), "10", "Muster", DE_IBAN)),
            List.of(
                transfer("E2E-1", "10", "Muster", DE_IBAN),
                transfer("E2E-1", "5", "Dupont", FR_IBAN)));
    for (List<Pain001.Transfer> transfers : bad) {
      assertThrows(
          IllegalArgumentException.class,
          () -> Pain001.write(initiation(transfers)),
          transfers.toString());
    }
    IllegalArgumentException named =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Pain001.write(
                    initiation(
                        List.of(
                            transfer("E2E-1", "10", "Muster GmbH", "DE00370400440532013000")))));
    assertThat(named.getMessage(), containsString("Muster GmbH"));
  }

  // ── Bacs Standard 18 ────────────────────────────────────────────────────────

  private static final Bacs18.Originator ORIGINATOR =
      new Bacs18.Originator("123456", "40-28-11", "12345678", "Corner Shop Ltd");

  private static Bacs18.Submission submission(List<Bacs18.Credit> credits) {
    // Created Tuesday 15 Sep 2026, processed Wednesday 16th: day 259 of the year.
    return new Bacs18.Submission(
        "3F9A1C",
        "042",
        LocalDate.of(2026, 9, 15),
        LocalDate.of(2026, 9, 16),
        ORIGINATOR,
        credits,
        "PAY260915-3F9A1C");
  }

  @Test
  @DisplayName(
      "A sterling run is a Standard 18 file: labels of 80, records of 100, a contra, totals")
  void aSterlingRunIsAStandard18File() {
    String file =
        Bacs18.write(
            submission(
                List.of(
                    new Bacs18.Credit(
                        "12-34-56",
                        "31415926",
                        new BigDecimal("35.00"),
                        "PAY260915-3F9A1C",
                        "Acme Ltd"),
                    new Bacs18.Credit(
                        "20-00-00",
                        "55779911",
                        new BigDecimal("1234.56"),
                        "PAY260915-3F9A1C",
                        "Muster & Sons (UK) Ltd"))));
    assertThat(file.endsWith("\r\n"), is(true));
    String[] r = file.split("\r\n");
    assertThat(r.length, is(10));
    assertThat(
        Arrays.asList(
            r[0].substring(0, 4), r[1].substring(0, 4), r[2].substring(0, 4), r[3].substring(0, 4)),
        is(List.of("VOL1", "HDR1", "HDR2", "UHL1")));
    assertThat(
        Arrays.asList(r[7].substring(0, 4), r[8].substring(0, 4), r[9].substring(0, 4)),
        is(List.of("EOF1", "EOF2", "UTL1")));
    for (int i : new int[] {0, 1, 2, 3, 7, 8, 9}) assertThat(r[i], r[i].length(), is(80));
    for (int i : new int[] {4, 5, 6}) assertThat(r[i], r[i].length(), is(100));

    assertThat(r[0], is("VOL13F9A1C " + " ".repeat(30) + "123456" + " ".repeat(32) + "1"));
    assertThat(r[1].substring(41, 47), is(" 26258"));
    assertThat(r[3].substring(4, 10), is(" 26259"));
    assertThat(r[3].substring(28, 40), is("1 DAILY  042"));
    assertThat(r[2].substring(0, 15), is("HDR2F0200000100"));

    // Destination, account type 0, code 99, originator, amount in pence, names cut to 18.
    assertThat(r[4].substring(0, 17), is("12345631415926099"));
    assertThat(r[4].substring(17, 31), is("40281112345678"));
    assertThat(r[4].substring(35, 46), is("00000003500"));
    assertThat(r[4].substring(46, 64), is("CORNER SHOP LTD   "));
    assertThat(r[4].substring(64, 82), is("PAY260915-3F9A1C  "));
    assertThat(r[5].substring(82, 100), is("MUSTER & SONS  UK "));

    // The contra debits the business's own account with the total.
    assertThat(r[6].substring(0, 17), is("40281112345678017"));
    assertThat(r[6].substring(35, 46), is("00000126956"));
    assertThat(r[6].substring(64, 82), is("CONTRA            "));
    assertThat(r[6].substring(82, 100), is(r[4].substring(46, 64)));

    assertThat(r[7].substring(4, 54), is(r[1].substring(4, 54)));
    assertThat(r[8].substring(4), is(r[2].substring(4)));
    assertThat(
        r[9].substring(0, 44),
        is("UTL1" + "0000000126956" + "0000000126956" + "0000001" + "0000002"));
  }

  @Test
  @DisplayName(
      "The processing day is the banking day before value, and today's value date is too late")
  void theBacsCycle() {
    // Monday 21 Sep 2026 value: processed Friday 18th.
    assertThat(Bacs18.processingDayFor(LocalDate.of(2026, 9, 21)), is(LocalDate.of(2026, 9, 18)));
    assertThat(Bacs18.processingDayFor(LocalDate.of(2026, 9, 17)), is(LocalDate.of(2026, 9, 16)));
    // Submitted Friday 18th: processed Monday 21st, in the account Tuesday 22nd.
    assertThat(Bacs18.earliestValueDate(LocalDate.of(2026, 9, 18)), is(LocalDate.of(2026, 9, 22)));
    assertThat(Bacs18.earliestValueDate(LocalDate.of(2026, 9, 15)), is(LocalDate.of(2026, 9, 17)));
    assertThat(Bacs18.julian(LocalDate.of(2027, 1, 1)), is(" 27001"));
  }

  @Test
  @DisplayName("What Bacs would refuse is refused before a file exists")
  void bacsRefusals() {
    Bacs18.Credit ok = new Bacs18.Credit("12-34-56", "31415926", BigDecimal.TEN, "REF", "Acme");
    List<Bacs18.Submission> bad =
        List.of(
            submission(List.of()),
            submission(
                List.of(new Bacs18.Credit("12-34-5", "31415926", BigDecimal.TEN, "REF", "Acme"))),
            submission(
                List.of(new Bacs18.Credit("12-34-56", "3141592", BigDecimal.TEN, "REF", "Acme"))),
            submission(
                List.of(new Bacs18.Credit("12-34-56", "31415926", BigDecimal.ZERO, "REF", "Acme"))),
            submission(
                List.of(
                    new Bacs18.Credit(
                        "12-34-56", "31415926", new BigDecimal("0.001"), "REF", "Acme"))),
            submission(
                List.of(
                    new Bacs18.Credit(
                        "12-34-56", "31415926", new BigDecimal("20000000.01"), "REF", "Acme"))),
            submission("3F9A1C", "042", 15, ORIGINATOR, ok),
            submission("3F9A1C", "042", 19, ORIGINATOR, ok),
            submission("000000", "042", 16, ORIGINATOR, ok),
            submission("3F9A1C", "42", 16, ORIGINATOR, ok),
            submission(
                "3F9A1C",
                "042",
                16,
                new Bacs18.Originator("12345", "402811", "12345678", "Shop"),
                ok),
            submission(
                "3F9A1C",
                "042",
                16,
                new Bacs18.Originator("123456", "402811", "12345678", "   "),
                ok));
    for (Bacs18.Submission s : bad) {
      assertThrows(IllegalArgumentException.class, () -> Bacs18.write(s), s.toString());
    }
    assertThat(PayingAccounts.serviceUserNumber(" 123 456 "), is("123456"));
    assertThat(PayingAccounts.serviceUserNumber(""), is(nullValue()));
    assertThrows(IllegalArgumentException.class, () -> PayingAccounts.serviceUserNumber("12345A"));
  }

  /** One credit, made 15 Sep 2026 and processed on a day of that month. */
  private static Bacs18.Submission submission(
      String serial,
      String fileNumber,
      int processingDay,
      Bacs18.Originator originator,
      Bacs18.Credit credit) {
    return new Bacs18.Submission(
        serial,
        fileNumber,
        LocalDate.of(2026, 9, 15),
        LocalDate.of(2026, 9, processingDay),
        originator,
        List.of(credit),
        "N");
  }

  // ── pain.002 ────────────────────────────────────────────────────────────────

  private static String report(String groupStatus, String... transactions) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.002.001.10\"><CstmrPmtStsRpt>"
        + "<GrpHdr><MsgId>RPT-0001</MsgId><CreDtTm>2026-09-16T08:00:00Z</CreDtTm></GrpHdr>"
        + "<OrgnlGrpInfAndSts><OrgnlMsgId>PAY260915-3F9A1C</OrgnlMsgId><OrgnlMsgNmId>pain.001.001.09</OrgnlMsgNmId>"
        + (groupStatus == null
            ? ""
            : "<GrpSts>" + groupStatus + "</GrpSts><StsRsnInf><Rsn><Cd>FF01</Cd></Rsn></StsRsnInf>")
        + "</OrgnlGrpInfAndSts><OrgnlPmtInfAndSts><OrgnlPmtInfId>PAY260915-3F9A1C</OrgnlPmtInfId>"
        + String.join("", transactions)
        + "</OrgnlPmtInfAndSts></CstmrPmtStsRpt></Document>";
  }

  private static String tx(String e2e, String status, String reasonCd, String prtry, String info) {
    String reason =
        reasonCd == null && prtry == null
            ? ""
            : "<StsRsnInf><Rsn>"
                + (reasonCd == null ? "<Prtry>" + prtry + "</Prtry>" : "<Cd>" + reasonCd + "</Cd>")
                + "</Rsn>"
                + (info == null ? "" : "<AddtlInf>" + info + "</AddtlInf>")
                + "</StsRsnInf>";
    return "<TxInfAndSts><OrgnlEndToEndId>"
        + e2e
        + "</OrgnlEndToEndId><TxSts>"
        + status
        + "</TxSts>"
        + reason
        + "<OrgnlTxRef><PmtTpInf><SvcLvl><Cd>SEPA</Cd></SvcLvl></PmtTpInf></OrgnlTxRef></TxInfAndSts>";
  }

  @Test
  @DisplayName("The bank's answer is read per payment: accepted, a close match, no match, rejected")
  void theBanksAnswerIsReadPerPayment() {
    Pain002.Report r =
        Pain002.read(
            report(
                null,
                tx("E2E-1", "ACCP", null, "MTCH", null),
                tx("E2E-2", "ACCP", null, "CMTC", "MUSTER HANDELS GMBH"),
                tx("E2E-3", "PDNG", null, "NMTC", null),
                tx("E2E-4", "RJCT", "AC04", null, "Account closed")));
    assertThat(r.messageId(), is("RPT-0001"));
    assertThat(r.originalMessageId(), is("PAY260915-3F9A1C"));
    assertThat(r.transactions().size(), is(4));
    Pain002.TransactionStatus matched = r.statusOf("E2E-1");
    assertThat(matched.held(), is(false));
    Pain002.TransactionStatus close = r.statusOf("E2E-2");
    assertThat(close.payeeMatch(), is("CMTC"));
    assertThat(close.matchedName(), is("MUSTER HANDELS GMBH"));
    assertThat(close.held(), is(true));
    assertThat(close.releasable(), is(true));
    Pain002.TransactionStatus none = r.statusOf("E2E-3");
    assertThat(none.held(), is(true));
    assertThat(none.releasable(), is(false));
    Pain002.TransactionStatus rejected = r.statusOf("E2E-4");
    assertThat(rejected.reasonCode(), is("AC04"));
    assertThat(rejected.matchedName(), is(nullValue()));
    assertThat(rejected.held(), is(true));
    assertThat(rejected.releasable(), is(false));
    // A reason code inside the original transaction reference is not the status reason.
    assertThat(matched.reasonCode(), is(nullValue()));
    assertThat(r.statusOf("E2E-9"), is(nullValue()));
  }

  @Test
  @DisplayName("A whole file rejected applies to every payment in it")
  void aRejectedFileRejectsEveryPayment() {
    Pain002.Report r = Pain002.read(report("RJCT"));
    assertThat(r.transactions().size(), is(0));
    Pain002.TransactionStatus any = r.statusOf("E2E-7");
    assertThat(any.status(), is("RJCT"));
    assertThat(any.reasonCode(), is("FF01"));
    assertThat(any.held(), is(true));
  }

  @Test
  @DisplayName(
      "A report that is not a pain.002 this can trust is refused, an entity never expanded")
  void untrustworthyReportsAreRefused() {
    List<String> bad =
        List.of(
            "",
            "not xml at all",
            "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"><CstmrCdtTrfInitn/></Document>",
            "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + report(null, tx("&xxe;", "ACCP", null, null, null))
                    .replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", ""),
            report(null, tx("E2E-1", "DONE", null, null, null)),
            report(null, tx("", "ACCP", null, null, null)),
            report(
                null,
                tx("E2E-1", "ACCP", null, null, null),
                tx("E2E-1", "RJCT", "AC04", null, null)),
            report(null),
            report(null, tx("E2E-1", "ACCP", null, null, null))
                .replace("<OrgnlMsgId>PAY260915-3F9A1C</OrgnlMsgId>", ""),
            report(null, tx("E2E-1", "ACCP", null, null, null)).replace("RPT-0001", ""),
            report(null, tx("E2E-1", "ACCP", null, null, null)) + " ".repeat(Pain002.MAX_CHARS));
    for (String xml : bad) {
      assertThrows(
          IllegalArgumentException.class,
          () -> Pain002.read(xml),
          xml.length() > 200 ? xml.substring(0, 200) : xml);
    }
    StringBuilder many = new StringBuilder();
    for (int i = 0; i <= Pain002.MAX_TRANSACTIONS; i++)
      many.append(tx("E" + i, "ACCP", null, null, null));
    assertThrows(IllegalArgumentException.class, () -> Pain002.read(report(null, many.toString())));
    // A proprietary reason that is not a payee check is not read as one.
    assertThat(
        Pain002.read(report(null, tx("E2E-1", "ACCP", null, "SOMETHING", null)))
            .statusOf("E2E-1")
            .payeeMatch(),
        is(nullValue()));
  }
}
