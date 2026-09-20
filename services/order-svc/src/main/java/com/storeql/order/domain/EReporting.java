package com.storeql.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * E-reporting: the transactions an invoice does not cover (18.9, second limb).
 *
 * <p>France's reform asks for two things, and 18.9 built one of them. The invoice limb covers a
 * sale to a business established in France. The reporting limb covers everything else subject to
 * French VAT — <b>a sale to a shopper, and a sale abroad</b> — plus, for services, the day the
 * money was actually received. A shop selling to the public issues no e-invoices at all and still
 * owes a report three times a month, so building only the invoice limb leaves a French retailer in
 * breach.
 *
 * <p>Three decisions shape this.
 *
 * <p><b>A sale is out of scope because an invoice already carries it, not because of a guess about
 * the buyer.</b> The e-invoice is the evidence that the other limb has it; anything else — a flag
 * on the customer, a VAT number that may have been typed wrong — would double-report or miss.
 *
 * <p><b>B2C is aggregated per day and per rate.</b> That is what the law asks, and it is also the
 * only form that does not put a shopper's basket into a tax filing.
 *
 * <p><b>Cross-border sales go line by line.</b> They have an invoice, a buyer and a country, and
 * the administration matches them against the other member state's records; an aggregate could not
 * be matched to anything.
 */
public final class EReporting {

  private EReporting() {}

  /** Transaction data: what was sold. */
  public static final String TRANSACTIONS = "EREPORTING_TX_FR";

  /** Payment data: when the money for a service arrived. */
  public static final String PAYMENTS = "EREPORTING_PAY_FR";

  public static final Set<String> RETURNS = Set.of(TRANSACTIONS, PAYMENTS);

  public static final String PENDING = "PENDING";
  public static final String ACCEPTED = "ACCEPTED";
  public static final String REJECTED = "REJECTED";

  /**
   * The longest period this service will report in one submission.
   *
   * <p>A sanity bound, not a rule of law: the calendar lives in tenant-svc and the caller passes
   * the period it asks for, so a mistyped date should be refused rather than sweep a year of sales
   * into one filing that looks complete.
   */
  public static final int MAX_PERIOD_DAYS = 31;

  /**
   * One rate's money within a day.
   *
   * @param vatCode the rate's code as the business's own VAT table names it, for the audit trail
   * @param vatRate the fraction applied, 0.2000 for 20 per cent
   */
  public record RateLine(String vatCode, BigDecimal vatRate, BigDecimal net, BigDecimal vat) {}

  /**
   * One day of B2C trade: how many operations, and the money split by rate.
   *
   * <p>The count sits on the <b>day</b> and not on each rate, which matters: a basket with a
   * zero-rated loaf and a standard-rated bottle is one transaction, and a count kept per rate would
   * report it as two. The law asks for the number of operations in the day and the totals per rate,
   * and those are two different shapes.
   */
  public record Day(LocalDate day, int transactionCount, List<RateLine> rates) {

    public Day {
      rates = rates == null ? List.of() : List.copyOf(rates);
    }

    public BigDecimal net() {
      return sum(rates.stream().map(RateLine::net).toList());
    }

    public BigDecimal vat() {
      return sum(rates.stream().map(RateLine::vat).toList());
    }
  }

  /**
   * One cross-border sale, reported at invoice level.
   *
   * @param buyerCountry taken from the buyer's VAT identifier rather than an address field, because
   *     the identifier is what the other administration matches on
   */
  public record CrossBorderLine(
      String invoiceNumber,
      LocalDate issueDate,
      String buyerCountry,
      String buyerVatId,
      String currency,
      BigDecimal net,
      BigDecimal vat) {}

  /**
   * What was reported for a period, and what the network said.
   *
   * @param payloadDigest SHA-256 of the payload, base64 — what the business puts on the filing it
   *     records in tenant-svc, so the two can be tied together without tenant-svc holding a copy
   * @param supersededBy set once a correction replaced this submission; the original stays
   */
  public record Submission(
      UUID id,
      UUID tenantId,
      String returnCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      int transactionCount,
      BigDecimal netTotal,
      BigDecimal vatTotal,
      String payload,
      String payloadDigest,
      String network,
      String provider,
      String status,
      String detail,
      String providerRef,
      int attempts,
      Instant createdAt,
      UUID createdBy,
      Instant transmittedAt,
      UUID supersedes,
      UUID supersededBy) {

    /** Whether this is the submission that stands, rather than one a correction has replaced. */
    public boolean stands() {
      return supersededBy == null;
    }
  }

  /** What a period's content came to, before it is written. */
  public record Content(
      String returnCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      List<Day> days,
      List<CrossBorderLine> crossBorder) {

    public Content {
      days = days == null ? List.of() : List.copyOf(days);
      crossBorder = crossBorder == null ? List.of() : List.copyOf(crossBorder);
    }

    /**
     * How many transactions the period covers.
     *
     * <p>Days carry a count of their own; a cross-border line is one sale. A period with neither is
     * still a report — see {@link #empty()}.
     */
    public int transactionCount() {
      return days.stream().mapToInt(Day::transactionCount).sum() + crossBorder.size();
    }

    public BigDecimal netTotal() {
      return sum(days.stream().map(Day::net).toList())
          .add(sum(crossBorder.stream().map(CrossBorderLine::net).toList()));
    }

    public BigDecimal vatTotal() {
      return sum(days.stream().map(Day::vat).toList())
          .add(sum(crossBorder.stream().map(CrossBorderLine::vat).toList()));
    }

    /**
     * Whether the period has nothing in it.
     *
     * <p>Reported anyway, deliberately: a period with no in-scope sales is a fact the
     * administration is owed, and silence is indistinguishable from a platform that stopped
     * working. A shop that closed for a fortnight files two empty reports rather than looking like
     * it evaded two.
     */
    public boolean empty() {
      return days.isEmpty() && crossBorder.isEmpty();
    }
  }

  private static BigDecimal sum(List<BigDecimal> values) {
    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal v : values) total = total.add(v == null ? BigDecimal.ZERO : v);
    return total;
  }

  /**
   * The period a submission may report.
   *
   * @return null when it is acceptable, else why it is not
   */
  public static String periodProblem(LocalDate start, LocalDate end, LocalDate asOf) {
    if (start == null || end == null || !end.isAfter(start)) {
      return "A period ends after it starts";
    }
    if (end.isAfter(asOf)) {
      return "A period is reported once it has ended, and this one ends " + end;
    }
    if (start.plusDays(MAX_PERIOD_DAYS).isBefore(end)) {
      return "A reporting period is at most " + MAX_PERIOD_DAYS + " days";
    }
    return null;
  }

  /**
   * The payload, as the platform hands it to the network.
   *
   * <p>The data, structured — not the PPF's own wire format, and deliberately so: a partner
   * platform (a PDP) takes a business's data and maps it to the administration's format at the
   * point of deposit, which is the service it is certified for. What the platform owes is that
   * every field the law lists is present, correct and traceable to the sales it came from; the
   * mapping belongs to the provider whose certification covers it.
   *
   * <p>Element and attribute names are the law's own vocabulary so a reader can check the document
   * against the instrument rather than against this code.
   */
  public static String payload(Content content, String sellerVatId, String sellerCountry) {
    StringBuilder xml = new StringBuilder(512);
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        .append("<EReporting xmlns=\"urn:storeql:ereporting:1\" stream=\"")
        .append(content.returnCode())
        .append("\">\n  <Emetteur>\n    <NumeroTVA>")
        .append(text(sellerVatId))
        .append("</NumeroTVA>\n    <Pays>")
        .append(text(sellerCountry))
        .append("</Pays>\n  </Emetteur>\n  <Periode debut=\"")
        .append(content.periodStart())
        .append("\" finExclue=\"")
        .append(content.periodEnd())
        .append("\" devise=\"")
        .append(text(content.currency()))
        .append("\"/>\n");
    if (!content.days().isEmpty()) {
      xml.append("  <DonneesTransaction>\n");
      for (Day d : content.days()) {
        xml.append("    <Journee date=\"")
            .append(d.day())
            .append("\" nombreOperations=\"")
            .append(d.transactionCount())
            .append("\">\n");
        for (RateLine r : d.rates()) {
          xml.append("      <Taux codeTVA=\"")
              .append(text(r.vatCode()))
              .append("\" tauxTVA=\"")
              .append(r.vatRate() == null ? "" : r.vatRate().toPlainString())
              .append("\" montantHT=\"")
              .append(r.net().toPlainString())
              .append("\" montantTVA=\"")
              .append(r.vat().toPlainString())
              .append("\"/>\n");
        }
        xml.append("    </Journee>\n");
      }
      xml.append("  </DonneesTransaction>\n");
    }
    if (!content.crossBorder().isEmpty()) {
      xml.append("  <OperationsTransfrontalieres>\n");
      for (CrossBorderLine c : content.crossBorder()) {
        xml.append("    <Operation numeroFacture=\"")
            .append(text(c.invoiceNumber()))
            .append("\" date=\"")
            .append(c.issueDate())
            .append("\" paysAcquereur=\"")
            .append(text(c.buyerCountry()))
            .append("\" numeroTVAAcquereur=\"")
            .append(text(c.buyerVatId()))
            .append("\" montantHT=\"")
            .append(c.net().toPlainString())
            .append("\" montantTVA=\"")
            .append(c.vat().toPlainString())
            .append("\"/>\n");
      }
      xml.append("  </OperationsTransfrontalieres>\n");
    }
    if (content.empty()) {
      // Said in words rather than left to an absence: a reader must be able to tell a period with
      // nothing in it from a document that lost its contents on the way.
      xml.append("  <Neant>true</Neant>\n");
    }
    xml.append("  <Totaux nombreOperations=\"")
        .append(content.transactionCount())
        .append("\" montantHT=\"")
        .append(content.netTotal().toPlainString())
        .append("\" montantTVA=\"")
        .append(content.vatTotal().toPlainString())
        .append("\"/>\n</EReporting>\n");
    return xml.toString();
  }

  /**
   * XML text, with the five predefined entities escaped. Nothing here is attacker-supplied, but a
   * business name with an ampersand in it is ordinary.
   */
  private static String text(String value) {
    if (value == null) return "";
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
