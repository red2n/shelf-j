package com.shelfj.purchase.service;

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
