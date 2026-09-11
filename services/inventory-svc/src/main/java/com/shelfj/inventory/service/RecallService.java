package com.shelfj.inventory.service;

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
            UUID.randomUUID(),
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

  public Cursor.Page<Summary> list(UUID tenantId, Status status, String after, Integer limit) {
    int lim = Cursor.clampLimit(limit);
    var rows = repo.list(tenantId, status, Cursor.decodeCreatedAtId(after), lim + 1);
    return Cursor.page(rows, lim, s -> s.header().openedAt() + "|" + s.header().id());
  }

  public Detail get(UUID tenantId, UUID recallId) {
    return repo.find(tenantId, recallId)
        .orElseThrow(() -> ApiException.notFound("RECALL_NOT_FOUND", "No such recall"));
  }

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
            UUID.randomUUID(),
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

  public Detail close(UUID tenantId, UUID actorId, UUID recallId, String notes) {
    repo.close(tenantId, recallId, actorId, blankToNull(notes));
    return get(tenantId, recallId);
  }

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
        UUID.randomUUID(),
        line.variantId(),
        blankToNull(line.batchNo()),
        line.expiryFrom(),
        line.expiryTo());
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
