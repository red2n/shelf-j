package com.shelfj.purchase.service;

import com.shelfj.purchase.domain.Domain.OpenClearing;
import com.shelfj.purchase.domain.Domain.SalesOrder;
import com.shelfj.purchase.domain.Domain.SalesTender;
import com.shelfj.purchase.domain.SalesPosting;
import com.shelfj.purchase.repo.SalesPostingRepository;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sales and tender posting to revenue and control accounts (17.7): turns what order-svc and
 * payment-svc announce about a sale into journals on this service's nominal ledger.
 *
 * <p>Postings are dated the day they are received, which for an event stream is the day of the
 * sale. They are not refused in a closed period the way a document is: the sale has happened and a
 * refused event would be redelivered forever; a sale reaching the ledger after its month is closed
 * is the kind of late item a close checklist looks for on the clearing report.
 */
@ApplicationScoped
public class SalesPostingService {

  static final String SALE_CONSUMER = "purchase-svc/sale-posting";
  static final String TENDER_CONSUMER = "purchase-svc/tender-posting";
  static final String REFUND_CONSUMER = "purchase-svc/refund-posting";
  static final String CHARGEBACK_CONSUMER = "purchase-svc/chargeback-posting";

  @Inject SalesPostingRepository repo;

  /** Posts a confirmed sale, once. */
  public boolean postSale(
      UUID eventId,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      BigDecimal total,
      BigDecimal taxAmount,
      String currency) {
    var posting = SalesPosting.sale(tenantId, orderId, storeId, total, taxAmount, today());
    return repo.recordSaleOnce(
        eventId,
        SALE_CONSUMER,
        new SalesOrder(tenantId, orderId, storeId, currency, total, taxAmount),
        posting);
  }

  /** Posts a captured tender, once per tender. */
  public boolean postTender(
      UUID paymentId, UUID tenantId, UUID orderId, UUID storeId, String method, BigDecimal amount) {
    var posting = SalesPosting.tender(tenantId, orderId, storeId, method, amount, today());
    return repo.recordTenderOnce(
        TENDER_CONSUMER,
        new SalesTender(tenantId, paymentId, orderId, storeId, method, amount),
        posting);
  }

  /** Posts a refund, once per event, against the sale when the ledger has it. */
  public boolean postRefund(
      UUID eventId,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      List<SalesPosting.Allocation> shares) {
    Optional<SalesOrder> sale = repo.findSale(tenantId, orderId);
    UUID store =
        storeId != null
            ? storeId
            : sale.map(SalesOrder::storeId)
                .orElseGet(() -> repo.findTenderStore(tenantId, orderId).orElse(null));
    var posting =
        SalesPosting.refund(
            tenantId,
            orderId,
            store,
            shares,
            sale.map(SalesOrder::total).orElse(null),
            sale.map(SalesOrder::taxAmount).orElse(null),
            sale.isPresent(),
            today());
    return repo.recordRefundOnce(eventId, REFUND_CONSUMER, posting);
  }

  /**
   * Posts the acquirer taking a disputed card payment (11.9), once per event: when the dispute was
   * opened with the money already gone, or later when the acquirer says it has taken it.
   */
  public boolean postChargebackWithdrawn(
      UUID eventId, UUID tenantId, UUID orderId, UUID storeId, BigDecimal amount, BigDecimal fee) {
    return repo.recordJournalOnce(
        eventId,
        CHARGEBACK_CONSUMER,
        SalesPosting.chargebackWithdrawn(tenantId, orderId, storeId, amount, fee, today()),
        "post chargeback");
  }

  /** Posts how a dispute ended, once per event. */
  public boolean postChargebackClosed(
      UUID eventId,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      BigDecimal amount,
      boolean won,
      boolean fundsWithdrawn) {
    return repo.recordJournalOnce(
        eventId,
        CHARGEBACK_CONSUMER,
        SalesPosting.chargebackClosed(
            tenantId, orderId, storeId, amount, won, fundsWithdrawn, today()),
        "post chargeback outcome");
  }

  /**
   * Orders whose receipts clearing has not netted to zero. Management only, like the trial balance.
   */
  public List<OpenClearing> openClearing(TenantContext ctx, String storeIdStr, int limit) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    UUID storeId = Parsing.optionalUuid(storeIdStr, "storeId");
    if (storeId != null) ctx.requireStoreAccess(storeId);
    return repo.findOpenClearing(ctx.requireTenantId(), storeId, Math.min(Math.max(limit, 1), 200));
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }
}
