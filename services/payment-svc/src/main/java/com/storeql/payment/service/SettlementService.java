package com.storeql.payment.service;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Settlements;
import com.storeql.payment.domain.Settlements.Batch;
import com.storeql.payment.domain.Settlements.BatchFile;
import com.storeql.payment.domain.Settlements.Line;
import com.storeql.payment.domain.Settlements.ParsedFile;
import com.storeql.payment.domain.Settlements.ParsedLine;
import com.storeql.payment.domain.Settlements.Unsettled;
import com.storeql.payment.dto.SettlementDtos;
import com.storeql.payment.repo.SettlementRepository;
import com.storeql.payment.repo.SettlementRepository.Target;
import com.storeql.payment.settlement.SettlementFileException;
import com.storeql.payment.settlement.SettlementFileParser;
import com.storeql.payment.settlement.SettlementFiles;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Reconciliation against the acquirer's settlement file (11.10). A payout's file is read, every
 * line is matched to the payment, refund or dispute this service holds, and what does not match
 * waits for a manager: point it at what it is about, accept a difference, or send its money to
 * unallocated receipts. A batch with nothing open is reconciled — at once when every line matched,
 * by a manager's sign-off otherwise — and only then is the ledger told, so card clearing empties
 * into the bank for exactly what the acquirer paid and for nothing a person has yet to look at.
 */
@ApplicationScoped
public class SettlementService {

  /** A card payment is usually paid out within three days; after that it is worth asking. */
  static final int DEFAULT_UNSETTLED_DAYS = 3;

  private static final int MAX_UNSETTLED_DAYS = 365;
  private static final int MAX_LINES_PAGE = 500;

  @Inject SettlementRepository repo;
  @Inject SettlementFiles files;
  @Inject TenantProfiles profiles;

  public List<String> formats() {
    return files.formats();
  }

  // ── import ──────────────────────────────────────────────────────────────────

  /**
   * Imports one payout's file.
   *
   * @throws ApiException 400 with the parser's code for a file that cannot be read; 400 {@code
   *     SETTLEMENT_REFERENCE_MISSING}, {@code SETTLEMENT_PAYOUT_DATE_MISSING}, {@code
   *     SETTLEMENT_PAYOUT_DATE_INVALID}, {@code SETTLEMENT_CURRENCY_MIXED}, {@code
   *     SETTLEMENT_OUT_OF_BALANCE}, {@code SETTLEMENT_STORE_UNKNOWN}; 409 {@code
   *     SETTLEMENT_ALREADY_IMPORTED}
   */
  public Batch importFile(
      UUID tenantId, UUID actorId, SettlementDtos.ImportRequest req, String idempotencyKey) {
    ParsedFile file;
    try {
      file = files.parse(req.format(), req.content());
    } catch (SettlementFileException e) {
      throw new ApiException(400, e.code(), e.getMessage(), List.of(), e);
    }
    String provider = words(req.provider(), "provider").toUpperCase(Locale.ROOT);
    String reference = file.reference() != null ? file.reference() : blankToNull(req.reference());
    if (reference == null) {
      throw ApiException.badRequest(
          "SETTLEMENT_REFERENCE_MISSING",
          "This layout does not carry the payout's number: give it with the import");
    }
    reference = words(reference, "reference");
    LocalDate payoutDate = file.payoutDate() != null ? file.payoutDate() : dateOf(req.payoutDate());
    if (payoutDate.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
      throw ApiException.badRequest(
          "SETTLEMENT_PAYOUT_DATE_INVALID", "A payout cannot have reached the bank in the future");
    }
    String currency = currencyOf(tenantId, file.currency(), req.currency());
    if (req.storeId() != null && !profiles.stores(tenantId, req.storeId()).has(req.storeId())) {
      throw ApiException.badRequest(
          "SETTLEMENT_STORE_UNKNOWN", "Not one of this business's stores");
    }
    Totals totals = Totals.of(file.lines());
    BigDecimal declared = file.declaredNet() != null ? file.declaredNet() : req.declaredNet();
    if (declared != null && declared.compareTo(totals.net) != 0) {
      throw ApiException.badRequest(
          "SETTLEMENT_OUT_OF_BALANCE",
          "The lines add up to "
              + totals.net.toPlainString()
              + " and the payout is "
              + declared.toPlainString()
              + ": the file is not the whole payout, or not this one");
    }
    Batch batch =
        new Batch(
            Ids.newId(),
            tenantId,
            req.storeId(),
            provider,
            reference,
            req.format().strip().toUpperCase(Locale.ROOT),
            currency,
            payoutDate,
            declared,
            totals.sales,
            totals.refunds,
            totals.chargebacks,
            totals.fees,
            totals.net,
            file.lines().size(),
            0,
            Settlements.EXCEPTIONS,
            blankToNull(idempotencyKey),
            actorId,
            Instant.now(),
            null,
            null);
    SettlementRepository.ImportResult result =
        repo.importBatch(batch, file.lines(), Events::settlementReconciled);
    if (result.outcome() == SettlementRepository.Imported.ALREADY_IMPORTED) {
      throw ApiException.conflict(
          "SETTLEMENT_ALREADY_IMPORTED",
          "This payout has been imported already: " + result.batch().id());
    }
    return result.batch();
  }

  /** What a file's lines add up to, each of the five as a positive figure a person would say. */
  private record Totals(
      BigDecimal sales,
      BigDecimal refunds,
      BigDecimal chargebacks,
      BigDecimal fees,
      BigDecimal net) {

    static Totals of(List<ParsedLine> lines) {
      BigDecimal sales = BigDecimal.ZERO;
      BigDecimal refunds = BigDecimal.ZERO;
      BigDecimal chargebacks = BigDecimal.ZERO;
      BigDecimal fees = BigDecimal.ZERO;
      BigDecimal net = BigDecimal.ZERO;
      for (ParsedLine l : lines) {
        net = net.add(l.net());
        fees = fees.add(l.fee());
        if (Settlements.SALE.equals(l.type())) sales = sales.add(l.gross());
        if (Settlements.REFUND.equals(l.type())) refunds = refunds.subtract(l.gross());
        if (Settlements.CHARGEBACK.equals(l.type())
            || Settlements.CHARGEBACK_REVERSAL.equals(l.type())) {
          chargebacks = chargebacks.subtract(l.gross());
        }
      }
      return new Totals(sales, refunds, chargebacks, fees, net);
    }
  }

  private String currencyOf(UUID tenantId, String ofFile, String typed) {
    String own = profiles.requireCurrency(tenantId);
    String said = ofFile != null ? ofFile : blankToNull(typed);
    if (said != null && !own.equalsIgnoreCase(said.strip())) {
      throw ApiException.badRequest(
          "SETTLEMENT_CURRENCY_MIXED",
          "The payout is in "
              + said.strip().toUpperCase(Locale.ROOT)
              + " and this business's payments are kept in "
              + own);
    }
    return own;
  }

  private static LocalDate dateOf(String typed) {
    if (blankToNull(typed) == null) {
      throw ApiException.badRequest(
          "SETTLEMENT_PAYOUT_DATE_MISSING",
          "This layout does not say when the payout reached the bank: give the date");
    }
    try {
      return LocalDate.parse(typed.strip());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400, "SETTLEMENT_PAYOUT_DATE_INVALID", "payoutDate is not an ISO date", List.of(), e);
    }
  }

  /** A name or a number a person typed: no control characters, which belong in no such thing. */
  private static String words(String text, String field) {
    String value = text.strip();
    if (value.chars().anyMatch(Character::isISOControl)) {
      throw ApiException.badRequest("VALIDATION_FAILED", field + " has characters no name has");
    }
    return value;
  }

  // ── reads ───────────────────────────────────────────────────────────────────

  public Cursor.Page<Batch> list(UUID tenantId, String status, String after, Integer limit) {
    String wanted = blankToNull(status);
    if (wanted != null) {
      wanted = wanted.toUpperCase(Locale.ROOT);
      if (!Settlements.STATUSES.contains(wanted)) {
        throw ApiException.badRequest("SETTLEMENT_STATUS_UNKNOWN", "Not a settlement status");
      }
    }
    int lim = Cursor.clampLimit(limit);
    List<Batch> rows = repo.list(tenantId, wanted, Cursor.decodeCreatedAtId(after), lim + 1);
    return Cursor.page(rows, lim, b -> b.importedAt() + "|" + b.id());
  }

  /**
   * A batch with a page of its lines in file order.
   *
   * @param onlyOpen keep to the lines still waiting for a decision
   * @param after the cursor a previous page gave
   */
  public BatchFile file(UUID tenantId, UUID id, boolean onlyOpen, String after, Integer limit) {
    Batch batch = require(tenantId, id);
    int lim = limit == null ? 100 : Math.max(1, Math.min(limit, MAX_LINES_PAGE));
    int afterLine = 0;
    if (blankToNull(after) != null) {
      try {
        afterLine = Integer.parseInt(Cursor.decode(after));
      } catch (NumberFormatException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Not a cursor this list gave", List.of(), e);
      }
    }
    List<Line> rows = repo.lines(tenantId, id, onlyOpen, afterLine, lim + 1);
    Cursor.Page<Line> page = Cursor.page(rows, lim, l -> Integer.toString(l.lineNo()));
    return new BatchFile(batch, page.items(), page.nextCursor());
  }

  /** Card payments taken at least {@code olderThanDays} ago that no settlement has covered. */
  public Cursor.Page<Unsettled> unsettled(
      UUID tenantId, UUID storeId, Integer olderThanDays, String after, Integer limit) {
    int days = olderThanDays == null ? DEFAULT_UNSETTLED_DAYS : olderThanDays;
    if (days < 0 || days > MAX_UNSETTLED_DAYS) {
      throw ApiException.badRequest(
          "VALIDATION_FAILED", "olderThanDays is between 0 and " + MAX_UNSETTLED_DAYS);
    }
    int lim = Cursor.clampLimit(limit);
    Instant before = Instant.now().minus(Duration.ofDays(days));
    List<Unsettled> rows =
        repo.unsettled(tenantId, storeId, before, Cursor.decodeCreatedAtId(after), lim + 1);
    return Cursor.page(rows, lim, u -> u.capturedAt() + "|" + u.tenderId());
  }

  // ── decisions ───────────────────────────────────────────────────────────────

  /**
   * Records a manager's decision on a line that did not match.
   *
   * @throws ApiException 404 {@code SETTLEMENT_NOT_FOUND}, {@code SETTLEMENT_LINE_NOT_FOUND},
   *     {@code SETTLEMENT_TARGET_NOT_FOUND}; 400 {@code SETTLEMENT_RESOLUTION_UNKNOWN}, {@code
   *     SETTLEMENT_TARGET_REQUIRED}, {@code SETTLEMENT_NOTE_REQUIRED}; 409 {@code
   *     SETTLEMENT_RECONCILED}, {@code SETTLEMENT_LINE_NOT_AN_EXCEPTION}, {@code
   *     SETTLEMENT_ALREADY_SETTLED}, {@code SETTLEMENT_AMOUNT_DIFFERS}, {@code
   *     SETTLEMENT_NOTHING_TO_ACCEPT}
   */
  public BatchFile resolve(
      UUID tenantId, UUID actorId, UUID batchId, UUID lineId, SettlementDtos.ResolveRequest req) {
    Batch batch = require(tenantId, batchId);
    if (batch.reconciled()) throw closed();
    Line line =
        repo.line(tenantId, batchId, lineId)
            .orElseThrow(() -> ApiException.notFound("SETTLEMENT_LINE_NOT_FOUND", "No such line"));
    String resolution = req.resolution().strip().toUpperCase(Locale.ROOT);
    if (!Settlements.RESOLUTIONS.contains(resolution)) {
      throw ApiException.badRequest(
          "SETTLEMENT_RESOLUTION_UNKNOWN",
          "One of " + String.join(", ", Settlements.RESOLUTIONS.stream().sorted().toList()));
    }
    String note = blankToNull(req.note());
    if (note == null && !Settlements.MATCHED_BY_HAND.equals(resolution)) {
      throw ApiException.badRequest(
          "SETTLEMENT_NOTE_REQUIRED", "Say why: the books will be read by somebody else");
    }
    Target target = targetOf(tenantId, line, resolution, req.targetId());
    SettlementRepository.Resolved outcome =
        repo.resolve(tenantId, batchId, lineId, resolution, target, note, actorId, Instant.now());
    switch (outcome) {
      case BATCH_CLOSED -> throw closed();
      case NOT_AN_EXCEPTION ->
          throw ApiException.conflict(
              "SETTLEMENT_LINE_NOT_AN_EXCEPTION", "This line matched: there is nothing to decide");
      case NOTHING_LINKED ->
          throw ApiException.conflict(
              "SETTLEMENT_NOTHING_TO_ACCEPT",
              "This line is linked to nothing: say which payment it is about");
      default -> {
        return file(tenantId, batchId, true, null, null);
      }
    }
  }

  private Target targetOf(UUID tenantId, Line line, String resolution, UUID targetId) {
    if (Settlements.UNALLOCATED.equals(resolution)) return null;
    if (targetId == null) {
      if (Settlements.MATCHED_BY_HAND.equals(resolution)) {
        throw ApiException.badRequest(
            "SETTLEMENT_TARGET_REQUIRED", "Say which payment, refund or dispute the line is about");
      }
      return null;
    }
    if (Settlements.FEE.equals(line.type()) || Settlements.ADJUSTMENT.equals(line.type())) {
      throw ApiException.conflict(
          "SETTLEMENT_LINE_NOT_AN_EXCEPTION",
          "A fee or an adjustment is about no one payment: it can only go to unallocated");
    }
    Target target =
        repo.target(tenantId, line.type(), targetId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "SETTLEMENT_TARGET_NOT_FOUND",
                        "No such " + kindOf(line.type()) + " in this business"));
    if (repo.settledElsewhere(tenantId, line.id(), target)) {
      throw ApiException.conflict(
          "SETTLEMENT_ALREADY_SETTLED",
          "Another settlement line already covers that " + kindOf(line.type()));
    }
    BigDecimal paid = chargeback(line.type()) ? line.net() : line.gross();
    if (Settlements.MATCHED_BY_HAND.equals(resolution) && target.held().compareTo(paid) != 0) {
      throw ApiException.conflict(
          "SETTLEMENT_AMOUNT_DIFFERS",
          "The line is for "
              + paid.toPlainString()
              + " and that "
              + kindOf(line.type())
              + " stands at "
              + target.held().toPlainString()
              + ": accept the difference or choose another");
    }
    return target;
  }

  private static boolean chargeback(String type) {
    return Settlements.CHARGEBACK.equals(type) || Settlements.CHARGEBACK_REVERSAL.equals(type);
  }

  private static String kindOf(String lineType) {
    if (Settlements.SALE.equals(lineType)) return "payment";
    return Settlements.REFUND.equals(lineType) ? "refund" : "dispute";
  }

  /**
   * Signs a batch off once every exception in it has been decided; the ledger is told in the same
   * transaction.
   *
   * @throws ApiException 409 {@code SETTLEMENT_RECONCILED} when it already was, {@code
   *     SETTLEMENT_HAS_EXCEPTIONS} while something is still open
   */
  public Batch reconcile(UUID tenantId, UUID actorId, UUID batchId) {
    Batch batch = require(tenantId, batchId);
    if (batch.reconciled()) throw closed();
    return repo.reconcile(tenantId, batchId, actorId, Instant.now(), Events::settlementReconciled)
        .orElseThrow(
            () -> {
              Batch now = require(tenantId, batchId);
              return now.reconciled()
                  ? closed()
                  : ApiException.conflict(
                      "SETTLEMENT_HAS_EXCEPTIONS",
                      now.openExceptions() + " lines are still waiting for a decision");
            });
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private Batch require(UUID tenantId, UUID id) {
    return repo.find(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("SETTLEMENT_NOT_FOUND", "No such settlement"));
  }

  private static ApiException closed() {
    return ApiException.conflict(
        "SETTLEMENT_RECONCILED", "This settlement is reconciled and in the books: it is closed");
  }

  /** The most lines one file may carry, as the layouts are told. */
  public static int maxLines() {
    return SettlementFileParser.MAX_LINES;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
