package com.shelfj.purchase.service;

import com.shelfj.service.OutboxRow;
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

  static OutboxRow goodsReceived(UUID tenantId, UUID grId, UUID poId) {
    return new OutboxRow(
        "GoodsReceived",
        "shelfj.purchase.goods-received",
        tenantId,
        grId,
        "{\"grId\":\"" + grId + "\",\"poId\":\"" + poId + "\"}");
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
