package com.shelfj.purchase.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Supplier payment runs (17.10): the records a run and the documents it settles are stored as. */
public final class PaymentRuns {

  private PaymentRuns() {}

  /** Built from what is due; nothing is paid, and the documents it holds are reserved to it. */
  public static final String PROPOSED = "PROPOSED";

  /** Released for payment by someone other than its proposer, or by the owner. */
  public static final String APPROVED = "APPROVED";

  /** Paid: the creditor cleared against the bank, the invoices settled, the advice sent. */
  public static final String PAID = "PAID";

  /** Abandoned before payment; its documents are free for the next run. */
  public static final String CANCELLED = "CANCELLED";

  /**
   * A payment run.
   *
   * @param reference what the bank statement and the remittance advice say, e.g.
   *     PAY-20260913-3F9A1C
   * @param payUpTo invoices due on or before this date are proposed
   * @param paymentDate the date the payment is made and posted
   */
  public record PaymentRun(
      UUID id,
      UUID tenantId,
      String reference,
      String status,
      LocalDate payUpTo,
      LocalDate paymentDate,
      String currency,
      BigDecimal total,
      UUID proposedBy,
      Instant proposedAt,
      UUID approvedBy,
      Instant approvedAt,
      UUID paidBy,
      Instant paidAt,
      UUID cancelledBy,
      Instant cancelledAt,
      String cancelReason,
      Instant createdAt) {}

  /**
   * One document a run settles.
   *
   * @param itemType {@code INVOICE} or {@code CREDIT_NOTE}
   * @param amount always positive; the type says whether it is paid or offset
   */
  public record Item(
      UUID id,
      UUID tenantId,
      UUID runId,
      UUID supplierId,
      UUID storeId,
      String itemType,
      UUID documentId,
      String reference,
      LocalDate documentDate,
      LocalDate dueDate,
      BigDecimal amount) {}

  /**
   * A run as read: its documents grouped by supplier, any supplier it can no longer pay and why,
   * and the suppliers themselves.
   */
  public record View(
      PaymentRun run,
      PaymentProposal.Result proposal,
      java.util.Map<UUID, Domain.Supplier> suppliers) {

    public View {
      suppliers = java.util.Map.copyOf(suppliers);
    }
  }
}
