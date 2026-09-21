package com.storeql.purchase.service;

import static com.storeql.purchase.domain.PaymentRuns.APPROVED;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Bacs18;
import com.storeql.purchase.domain.BankAccount;
import com.storeql.purchase.domain.BankFile;
import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.Money;
import com.storeql.purchase.domain.Pain001;
import com.storeql.purchase.domain.Pain002;
import com.storeql.purchase.domain.PayingAccounts;
import com.storeql.purchase.domain.PayingAccounts.PayingAccount;
import com.storeql.purchase.domain.PaymentProposal.SupplierPayment;
import com.storeql.purchase.domain.PaymentRuns.PayeeCheck;
import com.storeql.purchase.domain.PaymentRuns.PaymentRun;
import com.storeql.purchase.domain.PaymentRuns.View;
import com.storeql.purchase.dto.Dtos.PayingAccountRequest;
import com.storeql.purchase.dto.Dtos.ReleasePayeeRequest;
import com.storeql.purchase.repo.BankFileRepository;
import com.storeql.purchase.repo.BankFileRepository.StatusRow;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Bank-standard payment files and the bank's answer (17.12): the paying accounts a business pays
 * from, the file for an approved run in the format its bank takes — CSV, a SEPA pain.001 for euro,
 * a Bacs Standard 18 for sterling — the pain.002 status report read back against the run, and the
 * release of a close match a manager has checked.
 *
 * <p>Every step needs a management role and {@code finance.payments}, as the run itself does.
 */
@ApplicationScoped
public class BankFileService {

  public static final String CSV = "CSV";
  public static final String PAIN001 = "PAIN001";
  public static final String BACS18 = "BACS18";

  private static final Set<String> FORMATS = Set.of(CSV, PAIN001, BACS18);
  private static final String INVALID_ACCOUNT = "PURCHASE_PAYING_ACCOUNT_INVALID";

  @Inject PaymentRunService runs;
  @Inject BankFileRepository files;

  /** A file to hand the bank: its download name, media type and content. */
  public record File(String fileName, String contentType, String body) {}

  /** The paying account in force for each currency. */
  public List<PayingAccount> payingAccounts(TenantContext ctx) {
    return files.findPayingAccounts(PaymentRunService.requireFinance(ctx));
  }

  /**
   * Sets a currency's paying account. Earlier ones are kept.
   *
   * @throws ApiException 400 {@code PURCHASE_PAYING_ACCOUNT_INVALID} naming what is wrong
   */
  public PayingAccount setPayingAccount(
      TenantContext ctx, String currencyCode, PayingAccountRequest req) {
    UUID tenantId = PaymentRunService.requireFinance(ctx);
    String currency = Money.requireIso4217(currencyCode);
    BankAccount.Details details;
    String sun;
    try {
      details =
          BankAccount.details(
              req.accountName(), req.sortCode(), req.accountNumber(), req.iban(), req.bic());
      sun = PayingAccounts.serviceUserNumber(req.serviceUserNumber());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, INVALID_ACCOUNT, e.getMessage(), List.of(), e);
    }
    if (!details.payable()) {
      throw ApiException.badRequest(
          INVALID_ACCOUNT,
          "give the account's name and a sort code and account number, or an IBAN");
    }
    if (sun != null && details.sortCode() == null) {
      throw ApiException.badRequest(
          INVALID_ACCOUNT,
          "a Bacs service user number goes with a UK sort code and account number");
    }
    if ("EUR".equals(currency) && details.iban() == null) {
      throw ApiException.badRequest(INVALID_ACCOUNT, "a euro paying account needs its IBAN");
    }
    PayingAccount account =
        new PayingAccount(
            Ids.newId(),
            tenantId,
            currency,
            details.accountName(),
            details.sortCode(),
            details.accountNumber(),
            details.iban(),
            details.bic(),
            sun,
            ctx.userId(),
            Instant.now());
    files.insertPayingAccount(account);
    return files.findPayingAccount(tenantId, currency).orElse(account);
  }

  /**
   * The bank file for an approved or paid run.
   *
   * @param formatCode CSV (the default), PAIN001 or BACS18
   * @throws ApiException 400 {@code PURCHASE_BANK_FILE_FORMAT_UNKNOWN}; 409 for a run its checks
   *     refuse, {@code PURCHASE_BANK_FILE_FORMAT_UNSUPPORTED}, {@code
   *     PURCHASE_PAYING_ACCOUNT_MISSING}, {@code PURCHASE_PAYMENT_RUN_PAYING_ACCOUNT_CHANGED},
   *     {@code PURCHASE_BANK_FILE_PAYEE_UNSUPPORTED}, {@code PURCHASE_BANK_FILE_TOO_LATE} or {@code
   *     PURCHASE_BANK_FILE_REFUSED}
   */
  public File bankFile(TenantContext ctx, UUID runId, String formatCode) {
    String format =
        formatCode == null || formatCode.isBlank()
            ? CSV
            : formatCode.trim().toUpperCase(Locale.ROOT);
    if (!FORMATS.contains(format)) {
      throw ApiException.badRequest(
          "PURCHASE_BANK_FILE_FORMAT_UNKNOWN", "format must be CSV, PAIN001 or BACS18");
    }
    View view = runs.viewForBankFile(ctx, runId);
    return switch (format) {
      case PAIN001 -> pain001(view);
      case BACS18 -> bacs18(view);
      default -> csv(view);
    };
  }

  /**
   * Reads the bank's status report for a run's file and records what it said per supplier.
   *
   * @throws ApiException 400 {@code PURCHASE_STATUS_REPORT_INVALID}; 409 when the run is not an
   *     approved one, {@code PURCHASE_STATUS_REPORT_NOT_FOR_RUN} or {@code
   *     PURCHASE_STATUS_REPORT_UNKNOWN_PAYMENT}
   */
  public View recordStatusReport(TenantContext ctx, UUID runId, String xml) {
    View view = requireApproved(ctx, runId);
    PaymentRun run = view.run();
    Pain002.Report report;
    try {
      report = Pain002.read(xml);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "PURCHASE_STATUS_REPORT_INVALID", e.getMessage(), List.of(), e);
    }
    if (!run.reference().equals(report.originalMessageId())) {
      throw ApiException.conflict(
          "PURCHASE_STATUS_REPORT_NOT_FOR_RUN",
          "this report answers another file, not payment run " + run.reference());
    }
    Map<String, UUID> suppliersByPayment = new HashMap<>();
    for (SupplierPayment p : view.proposal().payments()) {
      suppliersByPayment.put(endToEndId(run.id(), p.supplierId()), p.supplierId());
    }
    List<String> unknown =
        report.transactions().stream()
            .map(Pain002.TransactionStatus::endToEndId)
            .filter(e2e -> !suppliersByPayment.containsKey(e2e))
            .sorted()
            .toList();
    if (!unknown.isEmpty()) {
      throw new ApiException(
          409,
          "PURCHASE_STATUS_REPORT_UNKNOWN_PAYMENT",
          "the report names payments this run does not make",
          unknown);
    }
    List<StatusRow> rows = new ArrayList<>();
    for (var e : suppliersByPayment.entrySet()) {
      Pain002.TransactionStatus status = report.statusOf(e.getKey());
      if (status != null) rows.add(new StatusRow(e.getValue(), status));
    }
    files.recordReport(Ids.newId(), run.tenantId(), run.id(), report, ctx.userId(), rows);
    return runs.get(ctx, runId);
  }

  /**
   * Releases a held close match a manager has checked.
   *
   * @throws ApiException 409 {@code PURCHASE_PAYEE_NOT_HELD}, {@code PURCHASE_PAYEE_NOT_RELEASABLE}
   *     or {@code PURCHASE_PAYEE_ALREADY_RELEASED}
   */
  public View release(TenantContext ctx, UUID runId, UUID supplierId, ReleasePayeeRequest req) {
    View view = requireApproved(ctx, runId);
    PayeeCheck check = view.checks().get(supplierId);
    if (check == null || !check.held()) {
      throw ApiException.conflict(
          "PURCHASE_PAYEE_NOT_HELD", "the bank has not held this supplier's payment in this run");
    }
    if (check.releasedAt() != null) {
      throw ApiException.conflict(
          "PURCHASE_PAYEE_ALREADY_RELEASED", "this supplier's payment was already released");
    }
    if (!check.releasable()) {
      throw ApiException.conflict(
          "PURCHASE_PAYEE_NOT_RELEASABLE",
          "only a close match can be released; a payee the bank could not match, or a rejected"
              + " payment, is not paid by this run");
    }
    if (!files.release(
        view.run().tenantId(),
        check.reportId(),
        runId,
        supplierId,
        ctx.userId(),
        req.reason().trim())) {
      throw ApiException.conflict(
          "PURCHASE_PAYEE_ALREADY_RELEASED", "this supplier's payment was already released");
    }
    return runs.get(ctx, runId);
  }

  /**
   * The end-to-end id a file gives a supplier's payment in a run: derived, so every file written
   * for the run names the payment the same way and the bank's report matches back to it.
   */
  static String endToEndId(UUID runId, UUID supplierId) {
    return Ids.derived(runId, "payment:" + supplierId).toString().replace("-", "");
  }

  // ── the formats ─────────────────────────────────────────────────────────────

  private static File csv(View view) {
    PaymentRun run = view.run();
    List<BankFile.Payment> rows =
        view.proposal().payments().stream()
            .map(
                p -> {
                  Supplier s = view.suppliers().get(p.supplierId());
                  return new BankFile.Payment(
                      s.bankAccountName(),
                      s.bankSortCode(),
                      s.bankAccountNumber(),
                      s.bankIban(),
                      s.bankBic(),
                      p.net(),
                      run.currency(),
                      run.reference());
                })
            .toList();
    return new File(BankFile.fileName(run.reference()), "text/csv", BankFile.csv(rows));
  }

  private File pain001(View view) {
    PaymentRun run = view.run();
    requireCurrency(run, "EUR", "a pain.001 SEPA credit transfer file pays a euro run");
    PayingAccount from = payingAccount(run, PayingAccount::sendsSepa, "its IBAN");
    requirePayees(
        view,
        s -> s.bankIban() != null,
        "a SEPA credit transfer pays an IBAN; these suppliers have none");
    List<Pain001.Transfer> transfers =
        view.proposal().payments().stream()
            .map(
                p -> {
                  Supplier s = view.suppliers().get(p.supplierId());
                  return new Pain001.Transfer(
                      endToEndId(run.id(), p.supplierId()),
                      p.net(),
                      new Pain001.Account(s.bankAccountName(), s.bankIban(), s.bankBic()),
                      run.reference());
                })
            .toList();
    try {
      String xml =
          Pain001.write(
              new Pain001.Initiation(
                  run.reference(),
                  run.approvedAt() == null ? Instant.now() : run.approvedAt(),
                  run.paymentDate(),
                  new Pain001.Account(from.accountName(), from.iban(), from.bic()),
                  transfers));
      return new File(fileName(run, "xml"), "application/xml", xml);
    } catch (IllegalArgumentException e) {
      throw new ApiException(409, "PURCHASE_BANK_FILE_REFUSED", e.getMessage(), List.of(), e);
    }
  }

  private File bacs18(View view) {
    PaymentRun run = view.run();
    requireCurrency(run, "GBP", "a Bacs Standard 18 file pays a sterling run");
    PayingAccount from =
        payingAccount(
            run, PayingAccount::sendsBacs, "its sort code, account number and service user number");
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate earliest = Bacs18.earliestValueDate(today);
    if (run.paymentDate().isBefore(earliest)) {
      throw new ApiException(
          409,
          "PURCHASE_BANK_FILE_TOO_LATE",
          "a Bacs file made today pays on "
              + earliest
              + " at the earliest; this run pays on "
              + run.paymentDate(),
          List.of(earliest.toString()));
    }
    requirePayees(
        view,
        s -> s.bankSortCode() != null && s.bankAccountNumber() != null,
        "Bacs pays a UK sort code and account number; these suppliers have none");
    List<Bacs18.Credit> credits =
        view.proposal().payments().stream()
            .map(
                p -> {
                  Supplier s = view.suppliers().get(p.supplierId());
                  return new Bacs18.Credit(
                      s.bankSortCode(),
                      s.bankAccountNumber(),
                      p.net(),
                      run.reference(),
                      s.bankAccountName());
                })
            .toList();
    // Named from the run, so a file sent twice carries the same serial and the bank refuses it.
    String hex = Ids.derived(run.id(), "bacs").toString().replace("-", "").toUpperCase(Locale.ROOT);
    String fileNumber =
        String.format(Locale.ROOT, "%03d", Integer.parseInt(hex.substring(6, 9), 16) % 1000);
    try {
      String body =
          Bacs18.write(
              new Bacs18.Submission(
                  hex.substring(0, 6),
                  fileNumber,
                  today,
                  Bacs18.processingDayFor(run.paymentDate()),
                  new Bacs18.Originator(
                      from.serviceUserNumber(),
                      from.sortCode(),
                      from.accountNumber(),
                      from.accountName()),
                  credits,
                  run.reference()));
      return new File(fileName(run, "txt"), "text/plain", body);
    } catch (IllegalArgumentException e) {
      throw new ApiException(409, "PURCHASE_BANK_FILE_REFUSED", e.getMessage(), List.of(), e);
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private View requireApproved(TenantContext ctx, UUID runId) {
    View view = runs.get(ctx, runId);
    if (!APPROVED.equals(view.run().status())) {
      throw ApiException.conflict(
          "PURCHASE_PAYMENT_RUN_NOT_AWAITING_BANK",
          "payment run "
              + view.run().reference()
              + " is "
              + view.run().status()
              + "; the bank's answer is read for an approved run not yet paid");
    }
    return view;
  }

  private static void requireCurrency(PaymentRun run, String currency, String what) {
    if (!currency.equals(run.currency())) {
      throw ApiException.conflict(
          "PURCHASE_BANK_FILE_FORMAT_UNSUPPORTED", what + "; this run pays " + run.currency());
    }
  }

  private static void requirePayees(View view, Predicate<Supplier> payable, String message) {
    List<String> cannot =
        view.proposal().payments().stream()
            .map(p -> view.suppliers().get(p.supplierId()))
            .filter(s -> !payable.test(s))
            .map(Supplier::name)
            .sorted()
            .toList();
    if (!cannot.isEmpty()) {
      throw new ApiException(409, "PURCHASE_BANK_FILE_PAYEE_UNSUPPORTED", message, cannot);
    }
  }

  /**
   * The account the run pays from. Refused when there is none that can send the format, or when the
   * account changed after the run was approved — a changed paying account is refused for the same
   * reason a changed supplier account is. A run approved before any account was set pays from the
   * first one set, and no later one.
   */
  private PayingAccount payingAccount(
      PaymentRun run, Predicate<PayingAccount> sends, String needs) {
    Optional<PayingAccount> current =
        files.findPayingAccount(run.tenantId(), run.currency()).filter(sends);
    if (current.isEmpty()) {
      throw ApiException.conflict(
          "PURCHASE_PAYING_ACCOUNT_MISSING",
          "set the " + run.currency() + " account payments are made from, with " + needs);
    }
    if (run.approvedAt() != null) {
      Optional<PayingAccount> approvedWith =
          files.findPayingAccountForRun(run.tenantId(), run.currency(), run.approvedAt());
      if (approvedWith.isPresent() && !approvedWith.get().id().equals(current.get().id())) {
        throw ApiException.conflict(
            "PURCHASE_PAYMENT_RUN_PAYING_ACCOUNT_CHANGED",
            "the account this run pays from changed after it was approved; cancel it and propose"
                + " again");
      }
    }
    return current.get();
  }

  private static String fileName(PaymentRun run, String extension) {
    return run.reference().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "") + "." + extension;
  }
}
