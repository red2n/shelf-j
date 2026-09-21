package com.storeql.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reconciliation against the acquirer's settlement file (11.10): which card payments a payout
 * covers, what the acquirer kept, and what does not agree with what this service holds.
 */
public final class Settlements {

  private Settlements() {}

  // ── a batch ─────────────────────────────────────────────────────────────────

  /** At least one line needs somebody's decision. */
  public static final String EXCEPTIONS = "EXCEPTIONS";

  /** Every exception has been decided; a manager has yet to sign the batch off. */
  public static final String READY = "READY";

  /** Closed: the ledger has been told. */
  public static final String RECONCILED = "RECONCILED";

  public static final Set<String> STATUSES = Set.of(EXCEPTIONS, READY, RECONCILED);

  // ── a line ──────────────────────────────────────────────────────────────────

  public static final String SALE = "SALE";
  public static final String REFUND = "REFUND";
  public static final String CHARGEBACK = "CHARGEBACK";

  /** A dispute won: the acquirer gives the money back. */
  public static final String CHARGEBACK_REVERSAL = "CHARGEBACK_REVERSAL";

  /** A charge that belongs to no one payment: a monthly fee, a terminal rental. */
  public static final String FEE = "FEE";

  /** Anything else the acquirer moves: a reserve held or released, a correction. */
  public static final String ADJUSTMENT = "ADJUSTMENT";

  public static final Set<String> TYPES =
      Set.of(SALE, REFUND, CHARGEBACK, CHARGEBACK_REVERSAL, FEE, ADJUSTMENT);

  public static final String MATCHED = "MATCHED";
  public static final String UNMATCHED = "UNMATCHED";

  /** Found, but for a different sum than the acquirer settled. */
  public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

  /** Found, and already settled by another line. */
  public static final String DUPLICATE = "DUPLICATE";

  /** A fee: there is nothing to match it to. */
  public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

  /** The match states that need a decision. */
  public static final Set<String> EXCEPTION_STATES = Set.of(UNMATCHED, AMOUNT_MISMATCH, DUPLICATE);

  /** A manager pointed the line at the payment, refund or dispute it is about. */
  public static final String MATCHED_BY_HAND = "MATCHED_BY_HAND";

  /** The payment is the right one and the difference is accepted, to be explained in the books. */
  public static final String DIFFERENCE_ACCEPTED = "DIFFERENCE_ACCEPTED";

  /** Nothing here answers to the line: its money goes to unallocated receipts. */
  public static final String UNALLOCATED = "UNALLOCATED";

  public static final Set<String> RESOLUTIONS =
      Set.of(MATCHED_BY_HAND, DIFFERENCE_ACCEPTED, UNALLOCATED);

  /**
   * One payout.
   *
   * @param declaredNet what the acquirer says it paid, when known; the lines must add up to it
   * @param openExceptions lines still waiting for a decision
   * @param reconciledBy who signed it off; null when every line matched and nobody had to
   */
  public record Batch(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String provider,
      String reference,
      String format,
      String currency,
      LocalDate payoutDate,
      BigDecimal declaredNet,
      BigDecimal salesAmount,
      BigDecimal refundAmount,
      BigDecimal chargebackAmount,
      BigDecimal feeAmount,
      BigDecimal netAmount,
      int lineCount,
      int openExceptions,
      String status,
      String idempotencyKey,
      UUID importedBy,
      Instant importedAt,
      UUID reconciledBy,
      Instant reconciledAt) {

    public boolean reconciled() {
      return RECONCILED.equals(status);
    }
  }

  /**
   * One line of a payout, signed from the business's side: a sale's gross is positive, a refund's
   * and a chargeback's negative; a fee is positive when it is a cost; net is gross less fee.
   *
   * @param originalReference for a refund or a chargeback, the payment it is about
   * @param expectedAmount what this service holds, when it differs from the line
   */
  public record Line(
      UUID id,
      UUID batchId,
      int lineNo,
      String type,
      String reference,
      String originalReference,
      BigDecimal gross,
      BigDecimal fee,
      BigDecimal net,
      Instant occurredAt,
      String matchStatus,
      UUID tenderId,
      UUID refundId,
      UUID disputeId,
      UUID storeId,
      BigDecimal expectedAmount,
      String resolution,
      UUID resolvedBy,
      Instant resolvedAt,
      String note) {

    /** Whether the line still waits for a decision. */
    public boolean open() {
      return resolution == null && EXCEPTION_STATES.contains(matchStatus);
    }
  }

  /** A line as a file gives it, before it is matched to anything. */
  public record ParsedLine(
      String type,
      String reference,
      String originalReference,
      BigDecimal gross,
      BigDecimal fee,
      BigDecimal net,
      Instant occurredAt) {}

  /**
   * What a file says, and what it says about itself: some layouts name the payout, its date, its
   * currency and the sum paid, and what the file says wins over what the person typed.
   */
  public record ParsedFile(
      List<ParsedLine> lines,
      String reference,
      LocalDate payoutDate,
      String currency,
      BigDecimal declaredNet) {
    public ParsedFile {
      lines = List.copyOf(lines);
    }
  }

  /**
   * What a reconciled batch moves in one store's books, each signed so that the usual case is
   * positive: into the bank, the acquirer's fees, out of card clearing, and into unallocated
   * receipts. {@code bank = clearing + unallocated - fees}, always.
   *
   * @param storeId null for what belongs to no store: a monthly fee, an unallocated line
   */
  public record StoreTotals(
      UUID storeId,
      BigDecimal bank,
      BigDecimal fees,
      BigDecimal clearing,
      BigDecimal unallocated) {}

  /** A batch with a page of its lines, as the detail screen shows it. */
  public record BatchFile(Batch batch, List<Line> lines, String nextCursor) {
    public BatchFile {
      lines = List.copyOf(lines);
    }
  }

  /** A card payment taken and not yet seen in any settlement. */
  public record Unsettled(
      UUID tenderId,
      UUID orderId,
      UUID storeId,
      String method,
      String reference,
      BigDecimal amount,
      Instant capturedAt) {}
}
