package com.shelfj.tenant.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Subscription billing and invoicing (21.9): what a business owes the platform for the plan it is
 * on, and the invoice that says so.
 */
public final class Subscriptions {

  private Subscriptions() {}

  // ── where a subscription stands ─────────────────────────────────────────────

  /** Free, so far. Nothing has been billed and the trial has not ended. */
  public static final String TRIALING = "TRIALING";

  /** Billed and paid up. */
  public static final String ACTIVE = "ACTIVE";

  /** An invoice is past its date. The business still trades: dunning decides when that stops. */
  public static final String PAST_DUE = "PAST_DUE";

  /**
   * Stopped for non-payment (21.12). The business cannot sign in; its invoices can still be paid.
   */
  public static final String SUSPENDED = "SUSPENDED";

  /** Over, by the business's choice or the platform's. */
  public static final String CANCELLED = "CANCELLED";

  /** The states a billing run looks at. */
  public static final Set<String> BILLABLE = Set.of(TRIALING, ACTIVE, PAST_DUE);

  public static final Set<String> STATUSES =
      Set.of(TRIALING, ACTIVE, PAST_DUE, SUSPENDED, CANCELLED);

  // ── what happened to it ─────────────────────────────────────────────────────

  public static final String STARTED = "STARTED";
  public static final String TRIAL_ENDED = "TRIAL_ENDED";
  public static final String RENEWED = "RENEWED";
  public static final String UPGRADED = "UPGRADED";
  public static final String DOWNGRADE_SCHEDULED = "DOWNGRADE_SCHEDULED";
  public static final String DOWNGRADED = "DOWNGRADED";
  public static final String CANCEL_SCHEDULED = "CANCEL_SCHEDULED";
  public static final String ENDED = "ENDED";
  public static final String WENT_PAST_DUE = "PAST_DUE";
  public static final String PAID_UP = "PAID_UP";
  public static final String DETAILS_CHANGED = "DETAILS_CHANGED";
  public static final String VAT_CHECKED = "VAT_CHECKED";

  /**
   * Where a VAT check came from. {@code SIMULATED} is the honest answer until there is a contract
   * with VIES — the same seam as the e-invoicing networks, and it says on the record that nothing
   * left the building.
   */
  public static final Set<String> VAT_CHECK_SOURCES = Set.of("VIES", "MANUAL", "SIMULATED");

  // ── an invoice ──────────────────────────────────────────────────────────────

  /** Issued and owed. */
  public static final String OPEN = "OPEN";

  /** Settled in full. */
  public static final String PAID = "PAID";

  /** Withdrawn. It keeps its number and its place in the sequence; a credit note explains it. */
  public static final String VOID = "VOID";

  /** Given up on: owed, and not expected (21.12). */
  public static final String UNCOLLECTIBLE = "UNCOLLECTIBLE";

  public static final String LINE_PLAN = "PLAN";
  public static final String LINE_PRORATION = "PRORATION";
  public static final String LINE_CREDIT = "CREDIT";

  public static final String BANK_TRANSFER = "BANK_TRANSFER";
  public static final String CARD = "CARD";

  // ── records ─────────────────────────────────────────────────────────────────

  /**
   * The platform's own identity, as an invoice prints it. Nothing can be billed without one: an
   * invoice with no seller is not an invoice anywhere the platform trades.
   *
   * @param taxRate the platform's own standard rate, for a sale in its own country
   * @param paymentTermsDays how long after issue an invoice falls due
   */
  public record BillingProfile(
      String legalName,
      String addressLine1,
      String addressLine2,
      String city,
      String postcode,
      String country,
      String vatNumber,
      String companyNumber,
      String invoicePrefix,
      int paymentTermsDays,
      BigDecimal taxRate,
      String bankDetails,
      UUID updatedBy,
      Instant updatedAt) {

    /** The seller block an invoice prints, as it stood the day it was issued. */
    public String snapshot() {
      StringBuilder out = new StringBuilder();
      for (String line :
          List.of(
              orEmpty(legalName),
              orEmpty(addressLine1),
              orEmpty(addressLine2),
              (orEmpty(city) + " " + orEmpty(postcode)).strip(),
              orEmpty(country),
              vatNumber == null || vatNumber.isBlank() ? "" : "VAT " + vatNumber,
              companyNumber == null || companyNumber.isBlank() ? "" : "Co. " + companyNumber)) {
        if (!line.isEmpty()) out.append(line).append('\n');
      }
      return out.toString().strip();
    }

    private static String orEmpty(String value) {
      return value == null ? "" : value;
    }
  }

  /**
   * The business as its invoice names it, and the evidence behind the VAT treatment.
   *
   * <p>Separate from the tenant's own row on purpose. Where a business is <em>established</em> is
   * not the same question as where its shops are: one with three shops in three countries is
   * established in one, and that one decides the VAT and prints on the invoice.
   *
   * @param country ISO 3166-1 alpha-2; seeded from the tenant at sign-up and the business's to
   *     correct
   * @param vatCheckedAt when the number was verified, or null — which is <em>not</em> the same as
   *     having no number. An unchecked number is treated as absent, because the reverse charge
   *     rests on it being real, and an audit asks for the date of the check.
   * @param vatCheckSource VIES, MANUAL or SIMULATED
   */
  public record Buyer(
      String name,
      String line1,
      String line2,
      String city,
      String postcode,
      String country,
      String vatNumber,
      Instant vatCheckedAt,
      UUID vatCheckedBy,
      String vatCheckSource) {

    /** Whether the VAT number has been checked — what the reverse charge rests on. */
    public boolean vatChecked() {
      return vatCheckedAt != null && vatNumber != null && !vatNumber.isBlank();
    }

    /** The block an invoice prints, as it stood the day it was issued. */
    public String snapshot() {
      StringBuilder out = new StringBuilder();
      for (String line :
          List.of(
              orEmpty(name),
              orEmpty(line1),
              orEmpty(line2),
              (orEmpty(city) + " " + orEmpty(postcode)).strip(),
              orEmpty(country),
              vatNumber == null || vatNumber.isBlank() ? "" : "VAT " + vatNumber)) {
        if (!line.isEmpty()) out.append(line).append('\n');
      }
      return out.toString().strip();
    }

    private static String orEmpty(String value) {
      return value == null ? "" : value;
    }
  }

  /**
   * What a business is signed up to.
   *
   * @param priceAmount what it was sold the plan at — locked here, so a change to the price list
   *     does not change what it pays
   * @param periodEnd the day the next period begins; this period does not include it
   * @param pendingPlanId a downgrade waiting for the period already paid for to finish
   */
  public record Subscription(
      UUID id,
      UUID tenantId,
      UUID planId,
      String status,
      BigDecimal priceAmount,
      String currency,
      String billingInterval,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate trialEnd,
      UUID pendingPlanId,
      boolean cancelAtPeriodEnd,
      Buyer buyer,
      String billingEmail,
      Instant startedAt,
      Instant cancelledAt,
      Instant createdAt,
      Instant updatedAt) {

    public boolean billable() {
      return BILLABLE.contains(status);
    }

    /** Whether it is still inside a trial on a day. */
    public boolean trialingOn(LocalDate day) {
      return trialEnd != null && day.isBefore(trialEnd);
    }
  }

  /**
   * A rate the platform charges in a country it has no choice about.
   *
   * @param effectiveFrom the day it takes effect; the rate in force is the latest one not after the
   *     invoice's date, so a change is a new row and never an edit
   */
  public record VatRate(String country, LocalDate effectiveFrom, BigDecimal rate, String note) {}

  /** One thing that happened to a subscription. */
  public record SubscriptionEvent(
      UUID id, String kind, String detail, UUID actorId, Instant createdAt) {}

  /**
   * An invoice. Never edited once issued: a mistake is withdrawn and explained, and the number
   * stays in the sequence either way.
   *
   * @param number gapless within its year
   * @param taxTreatment {@link BillingTax}
   * @param sellerSnapshot both sides as they stood the day it was issued, so it still explains
   *     itself when the business has moved and the platform has been renamed
   */
  public record Invoice(
      UUID id,
      UUID tenantId,
      UUID subscriptionId,
      String number,
      String status,
      LocalDate issueDate,
      LocalDate dueDate,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal netAmount,
      String taxTreatment,
      BigDecimal taxRate,
      BigDecimal taxAmount,
      BigDecimal totalAmount,
      BigDecimal amountPaid,
      String sellerSnapshot,
      String buyerSnapshot,
      String buyerVatNumber,
      String voidedReason,
      Instant createdAt,
      Instant updatedAt) {

    public boolean open() {
      return OPEN.equals(status);
    }

    public BigDecimal outstanding() {
      return totalAmount.subtract(amountPaid);
    }

    /** Whether it is past its date on a day, and still owed. */
    public boolean overdueOn(LocalDate day) {
      return open() && day.isAfter(dueDate);
    }
  }

  /**
   * One line of an invoice.
   *
   * @param kind PLAN for the period itself, PRORATION for a mid-period change, CREDIT for what is
   *     being given back
   */
  public record InvoiceLine(
      UUID id,
      int lineNo,
      String kind,
      String description,
      BigDecimal quantity,
      BigDecimal unitAmount,
      BigDecimal amount) {}

  /** Money received against an invoice. Append-only. */
  public record Payment(
      UUID id,
      UUID invoiceId,
      BigDecimal amount,
      String currency,
      String method,
      String provider,
      String providerRef,
      LocalDate receivedOn,
      UUID recordedBy,
      Instant createdAt) {}

  /** An invoice with its lines and what has been paid against it, as the detail screen shows it. */
  public record InvoiceFile(Invoice invoice, List<InvoiceLine> lines, List<Payment> payments) {
    public InvoiceFile {
      lines = List.copyOf(lines);
      payments = List.copyOf(payments);
    }
  }

  /** A subscription with its plan's name and its history, as the business sees it. */
  public record SubscriptionFile(
      Subscription subscription, String planCode, String planName, List<SubscriptionEvent> events) {
    public SubscriptionFile {
      events = List.copyOf(events);
    }
  }
}
