package com.storeql.purchase.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a payment run should pay (17.10): the pure part, so every rule can be tested without a
 * database.
 *
 * <p>Documents are grouped by supplier. A supplier is paid the sum of the invoices due less the
 * credit notes it has issued against returns — a supplier is not paid for goods it has already
 * credited. A supplier is only considered when it has an invoice in the run: credit notes alone are
 * left unallocated for the run that next pays that supplier. A supplier is excluded, and the
 * exclusion said, when it has no bank details to pay, or when its credits cover its invoices. A
 * supplier whose bank details changed shortly before the run is paid but flagged, because a changed
 * account on a supplier about to be paid is the pattern payment-diversion fraud leaves.
 */
public final class PaymentProposal {

  private PaymentProposal() {}

  public static final String INVOICE = "INVOICE";
  public static final String CREDIT_NOTE = "CREDIT_NOTE";

  public static final String NO_BANK_DETAILS = "NO_BANK_DETAILS";
  public static final String NET_NOT_POSITIVE = "NET_NOT_POSITIVE";
  public static final String BANK_DETAILS_CHANGED_RECENTLY = "BANK_DETAILS_CHANGED_RECENTLY";

  /** How recent a bank-details change must be to be flagged on a run. */
  public static final Duration RECENT_CHANGE = Duration.ofDays(14);

  /**
   * One document a run may settle.
   *
   * @param type {@link #INVOICE} or {@link #CREDIT_NOTE}
   * @param amount always positive; the type says whether it is paid or offset
   */
  public record Document(
      String type,
      UUID documentId,
      UUID supplierId,
      UUID storeId,
      String reference,
      LocalDate documentDate,
      LocalDate dueDate,
      BigDecimal amount) {}

  /** What the run needs to know about a supplier to decide whether it can be paid. */
  public record Payee(
      UUID supplierId, String name, boolean hasBankDetails, Instant bankDetailsChangedAt) {}

  /** A supplier the run pays, the documents it settles, and anything a reviewer should see. */
  public record SupplierPayment(
      UUID supplierId,
      String name,
      List<Document> documents,
      BigDecimal net,
      List<String> warnings) {

    public SupplierPayment {
      documents = List.copyOf(documents);
      warnings = List.copyOf(warnings);
    }
  }

  /** A supplier with something due that the run does not pay, and why. */
  public record Excluded(UUID supplierId, String name, String reason, BigDecimal net) {}

  /** The proposal: who is paid what, who is not and why. */
  public record Result(List<SupplierPayment> payments, List<Excluded> excluded) {

    public Result {
      payments = List.copyOf(payments);
      excluded = List.copyOf(excluded);
    }

    public BigDecimal total() {
      return payments.stream().map(SupplierPayment::net).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
  }

  /**
   * Builds the proposal.
   *
   * @param documents the invoices due and the unallocated credit notes, in any order
   * @param payees what is known about each supplier, by id
   * @param now the moment the run is proposed, for the recent-change flag
   * @return who is paid and who is not
   */
  public static Result build(List<Document> documents, Map<UUID, Payee> payees, Instant now) {
    Map<UUID, List<Document>> bySupplier = new LinkedHashMap<>();
    for (Document d : documents) {
      bySupplier.computeIfAbsent(d.supplierId(), k -> new ArrayList<>()).add(d);
    }
    List<SupplierPayment> payments = new ArrayList<>();
    List<Excluded> excluded = new ArrayList<>();
    for (var entry : bySupplier.entrySet()) {
      List<Document> docs = entry.getValue();
      if (docs.stream().noneMatch(d -> INVOICE.equals(d.type()))) continue;
      Payee payee = payees.get(entry.getKey());
      String name = payee == null ? entry.getKey().toString() : payee.name();
      BigDecimal net = BigDecimal.ZERO;
      for (Document d : docs) {
        net = INVOICE.equals(d.type()) ? net.add(d.amount()) : net.subtract(d.amount());
      }
      if (payee == null || !payee.hasBankDetails()) {
        excluded.add(new Excluded(entry.getKey(), name, NO_BANK_DETAILS, net));
        continue;
      }
      if (net.signum() <= 0) {
        excluded.add(new Excluded(entry.getKey(), name, NET_NOT_POSITIVE, net));
        continue;
      }
      List<String> warnings = new ArrayList<>();
      if (payee.bankDetailsChangedAt() != null
          && payee.bankDetailsChangedAt().isAfter(now.minus(RECENT_CHANGE))) {
        warnings.add(BANK_DETAILS_CHANGED_RECENTLY);
      }
      List<Document> ordered = new ArrayList<>(docs);
      ordered.sort(
          Comparator.comparing((Document d) -> CREDIT_NOTE.equals(d.type()))
              .thenComparing(d -> d.dueDate() == null ? LocalDate.MAX : d.dueDate())
              .thenComparing(Document::reference));
      payments.add(new SupplierPayment(entry.getKey(), name, ordered, net, warnings));
    }
    payments.sort(Comparator.comparing(SupplierPayment::name));
    excluded.sort(Comparator.comparing(Excluded::name));
    return new Result(payments, excluded);
  }
}
