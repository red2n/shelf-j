package com.shelfj.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * EMV terminals, and what one says when a card is presented to it (07.16).
 *
 * <p>A {@code CARD} tender used to be recorded because a cashier said so. Nothing asked a terminal
 * whether the card was approved, and nothing kept what a card receipt has to carry. The dispute
 * module said so in its own comment — "a card taken on a terminal the platform does not talk to".
 *
 * <p><b>The card number never reaches this platform.</b> The terminal runs the EMV transaction
 * itself and returns a verdict; the platform sends an amount and receives an outcome. That is the
 * whole architecture, and it is what keeps payment-svc out of PCI-DSS scope. {@link Outcome}
 * carries the four digits a receipt may print and nothing else of the number — there is no field
 * here that could hold more, and the schema has no column for it either.
 */
public final class Terminals {

  private Terminals() {}

  // ── the device ──────────────────────────────────────────────────────────────

  /**
   * {@code SIMULATED} is always deployed; a real vendor appears when its credentials are
   * configured.
   */
  public static final String SIMULATED = "SIMULATED";

  public static final Set<String> VENDORS =
      Set.of(SIMULATED, "STRIPE_TERMINAL", "ADYEN", "VERIFONE");

  public static final String ACTIVE = "ACTIVE";
  public static final String RETIRED = "RETIRED";

  /**
   * A terminal on a counter.
   *
   * @param serial the vendor's identifier for the physical device, as printed on it; null until it
   *     is paired
   */
  public record Terminal(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String label,
      String vendor,
      String serial,
      String status,
      String retiredReason,
      Instant createdAt,
      Instant updatedAt) {

    public boolean active() {
      return ACTIVE.equals(status);
    }
  }

  // ── an attempt ──────────────────────────────────────────────────────────────

  public static final String SALE = "SALE";
  public static final String REFUND = "REFUND";

  /** Asked of the terminal, and nothing back yet. The only state in which nothing is settled. */
  public static final String REQUESTED = "REQUESTED";

  public static final String APPROVED = "APPROVED";

  /** The card said no. A business outcome, not a fault: the cashier asks for another tender. */
  public static final String DECLINED = "DECLINED";

  /** Somebody pressed cancel — on the pinpad or at the till. */
  public static final String CANCELLED = "CANCELLED";

  /** The terminal could not be reached, or answered something unusable. */
  public static final String FAILED = "FAILED";

  /**
   * Nothing came back in time.
   *
   * <p>Distinct from {@link #FAILED} on purpose, and the most dangerous state there is: the card
   * may have been charged. It must never be retried silently, and the platform must not record a
   * tender for it — the cashier reads the terminal's own screen and the attempt is reconciled
   * against the acquirer's settlement file.
   */
  public static final String TIMED_OUT = "TIMED_OUT";

  public static final Set<String> STATES =
      Set.of(REQUESTED, APPROVED, DECLINED, CANCELLED, FAILED, TIMED_OUT);

  /** How the card was read. */
  public static final Set<String> ENTRY_MODES = Set.of("CHIP", "CONTACTLESS", "SWIPE", "MANUAL");

  /** How the cardholder was verified. {@code DEVICE} is a phone's own biometric. */
  public static final Set<String> VERIFICATIONS = Set.of("PIN", "SIGNATURE", "NONE", "DEVICE");

  /**
   * What the terminal said.
   *
   * @param panLast4 the four digits a receipt is permitted to print. There is no field for any
   *     other part of the number, deliberately: a record that cannot hold a PAN cannot leak one
   * @param aid the EMV application the card and terminal agreed on, e.g. {@code A0000000031010}
   * @param applicationLabel what the receipt prints for it, e.g. {@code VISA DEBIT}
   * @param detail the terminal's own words when it declined or failed, so a cashier is not left
   *     with "declined" and no reason
   */
  public record Outcome(
      String state,
      String scheme,
      String panLast4,
      String authCode,
      String aid,
      String applicationLabel,
      String entryMode,
      String verification,
      String providerRef,
      String detail) {

    public boolean approved() {
      return APPROVED.equals(state);
    }

    /**
     * Whether money may have moved without the platform knowing.
     *
     * <p>True only for a timeout. A decline took nothing and a failure never started; a timeout is
     * the one case where the platform must neither claim the money nor assume it was not taken.
     */
    public boolean uncertain() {
      return TIMED_OUT.equals(state);
    }
  }

  /**
   * One attempt at a terminal.
   *
   * @param paymentId the tender this became, once approved; null for anything that took no money
   */
  public record Attempt(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID terminalId,
      UUID orderId,
      BigDecimal amount,
      String currency,
      String kind,
      UUID refundOf,
      String state,
      String outcomeDetail,
      String scheme,
      String panLast4,
      String authCode,
      String aid,
      String applicationLabel,
      String entryMode,
      String verification,
      String providerRef,
      UUID paymentId,
      Instant requestedAt,
      UUID requestedBy,
      Instant settledAt) {

    public boolean approved() {
      return APPROVED.equals(state);
    }

    public boolean settled() {
      return !REQUESTED.equals(state);
    }

    /** What a receipt prints for this card, e.g. {@code VISA DEBIT ****1234 (CHIP, PIN)}. */
    public String receiptLine() {
      if (!approved()) return null;
      String label = applicationLabel != null ? applicationLabel : scheme;
      StringBuilder out = new StringBuilder(label).append(" ****").append(panLast4);
      if (entryMode != null) {
        out.append(" (").append(entryMode);
        if (verification != null) out.append(", ").append(verification);
        out.append(')');
      }
      return out.toString();
    }
  }

  /**
   * Whether a string could be a card number, so a caller cannot smuggle one in.
   *
   * <p>Belt to the gateway's braces. The gateway already refuses card-shaped request bodies, and
   * this refuses one that reached the service by any other route — a field renamed, a new client, a
   * test harness. The check is deliberately broad: thirteen or more digits, ignoring spaces and
   * dashes, that satisfy Luhn. A false positive costs a caller a clear refusal; a false negative
   * puts a PAN in a database that has nowhere to put it.
   */
  public static boolean looksLikeCardNumber(String value) {
    if (value == null) return false;
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= '0' && c <= '9') digits.append(c);
      else if (c != ' ' && c != '-') return false;
    }
    if (digits.length() < 13 || digits.length() > 19) return false;
    return luhnHolds(digits.toString());
  }

  private static boolean luhnHolds(String digits) {
    int sum = 0;
    boolean doubling = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (doubling) {
        d *= 2;
        if (d > 9) d -= 9;
      }
      sum += d;
      doubling = !doubling;
    }
    return sum % 10 == 0;
  }
}
