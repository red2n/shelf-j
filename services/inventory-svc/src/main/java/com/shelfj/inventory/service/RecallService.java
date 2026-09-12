package com.shelfj.inventory.service;

import com.shelfj.ids.Ids;
import com.shelfj.inventory.domain.Recall.ActiveItem;
import com.shelfj.inventory.domain.Recall.Detail;
import com.shelfj.inventory.domain.Recall.Disposition;
import com.shelfj.inventory.domain.Recall.Hazard;
import com.shelfj.inventory.domain.Recall.Header;
import com.shelfj.inventory.domain.Recall.Kind;
import com.shelfj.inventory.domain.Recall.Scope;
import com.shelfj.inventory.domain.Recall.Source;
import com.shelfj.inventory.domain.Recall.Status;
import com.shelfj.inventory.domain.Recall.StoreAction;
import com.shelfj.inventory.domain.Recall.Summary;
import com.shelfj.inventory.repo.RecallRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import com.shelfj.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Opening, working and closing product withdrawals and recalls. */
@ApplicationScoped
public class RecallService {

  static final String TOPIC_RECALL_OPENED = "shelfj.inventory.recall-opened";
  static final String TOPIC_STOCK_ADJUSTED = "shelfj.inventory.stock-adjusted";

  /** A recall notice lists a handful of lines; a hundred is a mistake, not a notice. */
  static final int MAX_SCOPE_LINES = 100;

  @Inject RecallRepository repo;

  public record OpenRecall(
      UUID tenantId,
      UUID actorId,
      String reference,
      Kind kind,
      Hazard hazard,
      String reason,
      String customerNotice,
      Source source,
      String sourceReference,
      List<ScopeLine> scope) {}

  public record ScopeLine(
      UUID variantId, String batchNo, LocalDate expiryFrom, LocalDate expiryTo) {}

  public record RecordStoreAction(
      UUID tenantId,
      UUID actorId,
      UUID recallId,
      UUID storeId,
      BigDecimal qtyFound,
      Disposition disposition,
      boolean noticeDisplayed,
      String notes) {}

  /**
   * Opens a recall or withdrawal, quarantining every batch its scope reaches.
   *
   * <p>A batch whose lot or date cannot be ruled out is held rather than cleared: a pack nobody can
   * rule out comes off sale. A RECALL additionally requires a customer notice, because that is the
   * difference between the two kinds.
   *
   * @param cmd the kind, hazard, scope lines and customer notice
   * @return the opened recall with its scope, held batches and actions
   * @throws ApiException {@code RECALL_SCOPE_REQUIRED} (400) with no scope lines; {@code
   *     RECALL_SCOPE_TOO_LARGE} (400) beyond the line cap; a 400 when a RECALL carries no customer
   *     notice
   */
  public Detail open(OpenRecall cmd) {
    if (cmd.scope().isEmpty()) {
      throw ApiException.badRequest("RECALL_SCOPE_REQUIRED", "A recall needs at least one item");
    }
    if (cmd.scope().size() > MAX_SCOPE_LINES) {
      throw ApiException.badRequest(
          "RECALL_SCOPE_TOO_LARGE", "A recall takes at most " + MAX_SCOPE_LINES + " items");
    }
    String notice = blankToNull(cmd.customerNotice());
    if (cmd.kind().tellsCustomers() && notice == null) {
      throw ApiException.badRequest(
          "RECALL_NOTICE_REQUIRED",
          "A recall tells customers what to do, so it needs the notice displayed in store");
    }
    List<Scope> scope = cmd.scope().stream().map(RecallService::toScope).toList();
    var header =
        new Header(
            Ids.newId(),
            cmd.tenantId(),
            cmd.reference().trim(),
            cmd.kind(),
            cmd.hazard(),
            cmd.reason().trim(),
            notice,
            cmd.source(),
            blankToNull(cmd.sourceReference()),
            Status.OPEN,
            cmd.actorId(),
            Instant.now(),
            null,
            null,
            null);
    return repo.open(
        header,
        scope,
        stores ->
            new OutboxRow(
                "RecallOpened",
                TOPIC_RECALL_OPENED,
                cmd.tenantId(),
                header.id(),
                Events.recallOpened(header, stores)));
  }

  /**
   * Cursor-paginated recalls for the tenant, with the counts a manager scans for.
   *
   * @param tenantId owning tenant
   * @param status restrict to one status, or {@code null} for all
   * @param after cursor from the previous page, or {@code null} to start
   * @param limit page size; clamped to the platform bounds
   * @return the page of summaries and its next cursor
   */
  public Cursor.Page<Summary> list(UUID tenantId, Status status, String after, Integer limit) {
    int lim = Cursor.clampLimit(limit);
    var rows = repo.list(tenantId, status, Cursor.decodeCreatedAtId(after), lim + 1);
    return Cursor.page(rows, lim, s -> s.header().openedAt() + "|" + s.header().id());
  }

  /**
   * One recall in full: header, scope, held batches and store actions.
   *
   * @param tenantId owning tenant
   * @param recallId the recall to read
   * @return the recall detail
   * @throws ApiException {@code RECALL_NOT_FOUND} (404) when no such recall exists in this tenant
   */
  public Detail get(UUID tenantId, UUID recallId) {
    return repo.find(tenantId, recallId)
        .orElseThrow(() -> ApiException.notFound("RECALL_NOT_FOUND", "No such recall"));
  }

  /**
   * Every variant currently under an open recall — what the till checks before selling.
   *
   * @param tenantId owning tenant
   * @return the actively recalled items
   */
  public List<ActiveItem> active(UUID tenantId) {
    return repo.listActive(tenantId);
  }

  /**
   * @param requireStoreAccess refuses a caller not assigned to the store; passed in so this class
   *     stays free of the request context
   */
  public StoreAction recordStoreAction(RecordStoreAction cmd, Consumer<UUID> requireStoreAccess) {
    requireStoreAccess.accept(cmd.storeId());
    if (cmd.qtyFound().signum() < 0 || cmd.qtyFound().stripTrailingZeros().scale() > 3) {
      throw ApiException.badRequest(
          "RECALL_QTY_INVALID", "qtyFound must be zero or more, to at most three decimal places");
    }
    var action =
        new StoreAction(
            Ids.newId(),
            cmd.storeId(),
            cmd.qtyFound(),
            BigDecimal.ZERO,
            cmd.disposition(),
            cmd.noticeDisplayed(),
            blankToNull(cmd.notes()),
            cmd.actorId(),
            Instant.now());
    return repo.recordStoreAction(
        cmd.tenantId(),
        cmd.recallId(),
        action,
        (variantId, delta) ->
            new OutboxRow(
                "StockAdjusted",
                TOPIC_STOCK_ADJUSTED,
                cmd.tenantId(),
                variantId,
                Events.stockAdjusted(cmd.tenantId(), cmd.storeId(), variantId, delta)));
  }

  /**
   * Releases one quarantined batch back to sale after it has been checked.
   *
   * <p>Only a batch the recall could not positively place in scope may be released — an {@code
   * IN_SCOPE} batch stays held.
   *
   * @param tenantId owning tenant
   * @param actorId the user releasing it, recorded against the release
   * @param recallId the recall holding the batch
   * @param batchId the batch to release
   * @param reason why it was cleared; required
   * @param requireStoreAccess refuses a caller not assigned to the batch's store; passed in so this
   *     class stays free of the request context
   * @return the recall as it now stands
   * @throws ApiException {@code RECALL_NOT_FOUND} (404) when no such recall exists; a conflict when
   *     the batch is in scope and therefore not releasable
   */
  public Detail release(
      UUID tenantId,
      UUID actorId,
      UUID recallId,
      UUID batchId,
      String reason,
      Consumer<UUID> requireStoreAccess) {
    repo.release(tenantId, recallId, batchId, reason.trim(), actorId, requireStoreAccess);
    return get(tenantId, recallId);
  }

  /**
   * Closes a recall once every affected store has accounted for its stock.
   *
   * @param tenantId owning tenant
   * @param actorId the user closing it
   * @param recallId the recall to close
   * @param notes closing notes, or {@code null}
   * @return the closed recall
   * @throws ApiException {@code RECALL_NOT_FOUND} (404) when no such recall exists; a conflict when
   *     stores are still outstanding
   */
  public Detail close(UUID tenantId, UUID actorId, UUID recallId, String notes) {
    repo.close(tenantId, recallId, actorId, blankToNull(notes));
    return get(tenantId, recallId);
  }

  /**
   * Cancels a recall raised in error, releasing everything it held.
   *
   * <p>Distinct from closing it: cancelling says the recall should never have been raised, so the
   * stock was never actually affected.
   *
   * @param tenantId owning tenant
   * @param actorId the user cancelling it
   * @param recallId the recall to cancel
   * @param reason why it was raised in error; required
   * @return the cancelled recall
   * @throws ApiException {@code RECALL_NOT_FOUND} (404) when no such recall exists; a conflict when
   *     stock has already been disposed of under it
   */
  public Detail cancel(UUID tenantId, UUID actorId, UUID recallId, String reason) {
    repo.cancel(tenantId, recallId, actorId, reason.trim());
    return get(tenantId, recallId);
  }

  static Scope toScope(ScopeLine line) {
    if (line.expiryFrom() != null
        && line.expiryTo() != null
        && line.expiryFrom().isAfter(line.expiryTo())) {
      throw ApiException.badRequest(
          "RECALL_DATES_INVERTED", "expiryFrom must not be after expiryTo");
    }
    return new Scope(
        Ids.newId(),
        line.variantId(),
        blankToNull(line.batchNo()),
        line.expiryFrom(),
        line.expiryTo());
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
