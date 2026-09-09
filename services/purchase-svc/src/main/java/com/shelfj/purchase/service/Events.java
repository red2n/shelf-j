package com.shelfj.purchase.service;

import com.shelfj.events.EventPayload;
import com.shelfj.purchase.domain.Domain.GoodsReceiptLine;
import com.shelfj.service.OutboxRow;
import java.util.List;
import java.util.UUID;

/** Outbox event factory for purchase-svc. */
final class Events {

  private Events() {}

  static OutboxRow purchaseOrderCreated(UUID tenantId, UUID poId) {
    return new OutboxRow(
        "PurchaseOrderCreated",
        "shelfj.purchase.purchase-order-created",
        tenantId,
        poId,
        "{\"poId\":\"" + poId + "\"}");
  }

  /**
   * Cancellation carries the reason, not just the id: a consumer reconciling open commitments needs
   * to distinguish a supplier-side failure from a buyer-side change of mind without calling back.
   * The reason is caller-supplied text, so it goes through {@link EventPayload#esc} — a quote in it
   * must not be able to corrupt the event JSON.
   */
  static OutboxRow purchaseOrderCancelled(UUID tenantId, UUID poId, String reason) {
    return new OutboxRow(
        "PurchaseOrderCancelled",
        "shelfj.purchase.purchase-order-cancelled",
        tenantId,
        poId,
        "{\"poId\":\"" + poId + "\",\"reason\":\"" + EventPayload.esc(reason) + "\"}");
  }

  /**
   * The stock movements inventory-svc writes from this event are labelled {@code ref_type='GRN'},
   * so {@code refId} has to be the goods receipt (SJ-D21). It carried the <em>purchase order</em>
   * id instead.
   *
   * <p>That was survivable while a purchase order could only ever have one receipt — the two ids
   * were in one-to-one correspondence, so citing the order still identified the delivery. Partial
   * receipt ends that: a purchase order now has many receipts, and every movement from every
   * delivery cited the same id under a label claiming to name a specific one. A goods-in
   * discrepancy could not be traced from the stock movement back to the delivery note, which is the
   * entire purpose of that reference.
   *
   * <p>The order is still one hop away — {@code goods_receipts.po_id} — and is carried here as
   * {@code poId} for consumers that want it without another lookup. <b>Movements written before
   * this fix still hold the purchase order id</b>; they cannot be corrected from here without
   * reaching into another service's schema (golden rule #1), and there was exactly one receipt per
   * order back then, so nothing was lost that a join cannot recover.
   */
  static OutboxRow goodsReceived(
      UUID tenantId, UUID grId, UUID storeId, UUID poId, List<GoodsReceiptLine> lines) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(grId)
        .append("\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"storeId\":\"")
        .append(storeId)
        .append("\",\"refId\":\"")
        .append(grId)
        .append("\",\"poId\":\"")
        .append(poId)
        .append("\",\"lines\":[");
    for (int i = 0; i < lines.size(); i++) {
      if (i > 0) sb.append(",");
      GoodsReceiptLine l = lines.get(i);
      sb.append("{\"variantId\":\"")
          .append(l.variantId())
          .append("\",\"qty\":")
          .append(l.qtyReceived())
          .append("}");
    }
    sb.append("]}");
    return new OutboxRow(
        "GoodsReceived", "shelfj.purchase.goods-received", tenantId, grId, sb.toString());
  }

  static OutboxRow intercompanyInvoiceRaised(UUID tenantId, UUID invoiceId) {
    return new OutboxRow(
        "IntercompanyInvoiceRaised",
        "shelfj.purchase.intercompany-invoice-raised",
        tenantId,
        invoiceId,
        "{\"invoiceId\":\"" + invoiceId + "\"}");
  }
}
