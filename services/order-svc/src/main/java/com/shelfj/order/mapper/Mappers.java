package com.shelfj.order.mapper;

import com.shelfj.order.domain.Domain.ExceptionRow;
import com.shelfj.order.domain.Domain.GiftCard;
import com.shelfj.order.domain.Domain.GiftCardTransaction;
import com.shelfj.order.domain.Domain.Layaway;
import com.shelfj.order.domain.Domain.LayawayDeposit;
import com.shelfj.order.domain.Domain.LayawayItem;
import com.shelfj.order.domain.Domain.Order;
import com.shelfj.order.domain.Domain.OrderItem;
import com.shelfj.order.domain.Domain.OrderReceipt;
import com.shelfj.order.domain.Domain.OrderStatusHistory;
import com.shelfj.order.domain.Domain.PosLogEntry;
import com.shelfj.order.domain.Domain.PosVoidLog;
import com.shelfj.order.domain.Domain.Return;
import com.shelfj.order.domain.Domain.ReturnItem;
import com.shelfj.order.domain.Domain.SalesByHourRow;
import com.shelfj.order.domain.Domain.SalesByStaffRow;
import com.shelfj.order.domain.Domain.SpecialOrder;
import com.shelfj.order.domain.Domain.SpecialOrderItem;
import com.shelfj.order.dto.Dtos.ExceptionRowResponse;
import com.shelfj.order.dto.Dtos.GiftCardResponse;
import com.shelfj.order.dto.Dtos.GiftCardTransactionResponse;
import com.shelfj.order.dto.Dtos.LayawayDepositResponse;
import com.shelfj.order.dto.Dtos.LayawayItemResponse;
import com.shelfj.order.dto.Dtos.LayawayResponse;
import com.shelfj.order.dto.Dtos.OrderItemResponse;
import com.shelfj.order.dto.Dtos.OrderReceiptResponse;
import com.shelfj.order.dto.Dtos.OrderResponse;
import com.shelfj.order.dto.Dtos.OrderStatusHistoryResponse;
import com.shelfj.order.dto.Dtos.OrderSummaryResponse;
import com.shelfj.order.dto.Dtos.PosLogEntryResponse;
import com.shelfj.order.dto.Dtos.ReturnItemResponse;
import com.shelfj.order.dto.Dtos.ReturnResponse;
import com.shelfj.order.dto.Dtos.SalesByHourRowResponse;
import com.shelfj.order.dto.Dtos.SalesByStaffRowResponse;
import com.shelfj.order.dto.Dtos.SpecialOrderItemResponse;
import com.shelfj.order.dto.Dtos.SpecialOrderResponse;
import com.shelfj.order.dto.Dtos.VoidResponse;
import java.time.Instant;
import java.util.List;

/** Entity → DTO mappers. Entities never cross the HTTP boundary. */
public final class Mappers {

  private Mappers() {}

  public static OrderItemResponse toDto(OrderItem i) {
    return new OrderItemResponse(
        str(i.id()), str(i.variantId()), i.qty(), i.unitPrice(), i.lineTotal(), i.notes());
  }

  public static OrderResponse toDto(Order o, List<OrderItem> items) {
    return new OrderResponse(
        str(o.id()),
        str(o.storeId()),
        str(o.customerId()),
        o.channel(),
        o.fulfilmentType(),
        o.status(),
        o.subtotal(),
        o.taxAmount(),
        o.discountAmount(),
        o.promotionDiscount() == null ? java.math.BigDecimal.ZERO : o.promotionDiscount(),
        o.total(),
        o.currency(),
        o.notes(),
        ts(o.createdAt()),
        ts(o.updatedAt()),
        items.stream().map(Mappers::toDto).toList(),
        o.taxExempt(),
        o.exemptReason(),
        o.deliveryLine1(),
        o.deliveryLine2(),
        o.deliveryCity(),
        o.deliveryPostalCode(),
        o.deliveryRecipientName(),
        o.deliveryRecipientPhone(),
        o.contactPhone(),
        o.paymentMethod());
  }

  public static OrderSummaryResponse toSummary(Order o) {
    return new OrderSummaryResponse(
        str(o.id()),
        str(o.storeId()),
        str(o.customerId()),
        o.channel(),
        o.fulfilmentType(),
        o.status(),
        o.subtotal(),
        o.taxAmount(),
        o.discountAmount(),
        o.total(),
        o.currency(),
        ts(o.createdAt()),
        ts(o.updatedAt()),
        o.paymentMethod());
  }

  public static OrderStatusHistoryResponse toDto(OrderStatusHistory h) {
    return new OrderStatusHistoryResponse(
        str(h.id()),
        str(h.orderId()),
        h.fromStatus(),
        h.toStatus(),
        h.reason(),
        str(h.changedBy()),
        ts(h.changedAt()));
  }

  public static ReturnItemResponse toDto(ReturnItem ri) {
    return new ReturnItemResponse(
        str(ri.id()), str(ri.variantId()), ri.qty(), ri.refundAmount(), ri.condition());
  }

  public static ReturnResponse toDto(Return r, List<ReturnItem> items) {
    return new ReturnResponse(
        str(r.id()),
        str(r.orderId()),
        str(r.storeId()),
        r.reason(),
        r.refundAmount(),
        r.refundMethod(),
        r.status(),
        ts(r.createdAt()),
        ts(r.completedAt()),
        items.stream().map(Mappers::toDto).toList());
  }

  public static VoidResponse toDto(PosVoidLog vl) {
    return new VoidResponse(str(vl.orderId()), vl.reason(), ts(vl.voidedAt()));
  }

  public static LayawayItemResponse toDto(LayawayItem li) {
    return new LayawayItemResponse(
        str(li.id()), str(li.variantId()), li.qty(), li.unitPrice(), li.lineTotal());
  }

  public static LayawayDepositResponse toDto(LayawayDeposit d) {
    return new LayawayDepositResponse(
        str(d.id()), d.amount(), d.paymentMethod(), d.reference(), ts(d.paidAt()));
  }

  public static LayawayResponse toDto(
      Layaway l, List<LayawayItem> items, List<LayawayDeposit> deposits) {
    return new LayawayResponse(
        str(l.id()),
        str(l.storeId()),
        str(l.customerId()),
        l.totalAmount(),
        l.depositPaid(),
        l.balance(),
        l.status(),
        l.notes(),
        ts(l.createdAt()),
        ts(l.dueDate()),
        ts(l.completedAt()),
        ts(l.cancelledAt()),
        items.stream().map(Mappers::toDto).toList(),
        deposits.stream().map(Mappers::toDto).toList());
  }

  public static GiftCardResponse toDto(GiftCard gc) {
    return new GiftCardResponse(
        str(gc.id()),
        str(gc.storeId()),
        gc.code(),
        gc.initialBalance(),
        gc.currentBalance(),
        gc.status(),
        gc.currency(),
        ts(gc.issuedAt()),
        ts(gc.expiresAt()));
  }

  public static GiftCardTransactionResponse toDto(GiftCardTransaction tx) {
    return new GiftCardTransactionResponse(
        str(tx.id()),
        tx.txType(),
        tx.amount(),
        tx.balanceBefore(),
        tx.balanceAfter(),
        str(tx.orderId()),
        tx.reference(),
        ts(tx.createdAt()));
  }

  public static SpecialOrderItemResponse toDto(SpecialOrderItem i) {
    return new SpecialOrderItemResponse(
        str(i.id()), str(i.variantId()), i.qty(), i.unitPrice(), i.lineTotal(), i.notes());
  }

  public static SpecialOrderResponse toDto(SpecialOrder so, List<SpecialOrderItem> items) {
    return new SpecialOrderResponse(
        str(so.id()),
        str(so.storeId()),
        str(so.customerId()),
        so.customerName(),
        so.customerPhone(),
        so.customerEmail(),
        so.deliveryAddress(),
        so.requestedDeliveryDate() != null ? so.requestedDeliveryDate().toString() : null,
        so.notes(),
        so.status(),
        so.subtotal(),
        so.total(),
        so.currency(),
        ts(so.createdAt()),
        ts(so.updatedAt()),
        items.stream().map(Mappers::toDto).toList());
  }

  public static ExceptionRowResponse toDto(ExceptionRow r) {
    return new ExceptionRowResponse(
        r.groupKey(),
        r.discounts(),
        r.discountAmount(),
        r.voids(),
        r.noSales(),
        r.sales(),
        r.salesValue());
  }

  public static PosLogEntryResponse toDto(PosLogEntry e) {
    return new PosLogEntryResponse(
        str(e.id()),
        str(e.orderId()),
        str(e.storeId()),
        str(e.cashierId()),
        e.subtotal(),
        e.taxAmount(),
        e.discountAmount(),
        e.total(),
        e.currency(),
        e.taxExempt(),
        e.exemptReason(),
        ts(e.transactionTs()),
        ts(e.createdAt()));
  }

  public static OrderReceiptResponse toDto(OrderReceipt r) {
    return new OrderReceiptResponse(
        str(r.id()),
        str(r.orderId()),
        r.receiptType(),
        r.emailedTo(),
        r.printCount(),
        ts(r.generatedAt()));
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }

  public static SalesByHourRowResponse toDto(SalesByHourRow r) {
    return new SalesByHourRowResponse(
        r.hourOfDay(), r.orders(), r.grossAmount(), r.discountAmount(), r.averageBasket());
  }

  public static SalesByStaffRowResponse toDto(SalesByStaffRow r) {
    return new SalesByStaffRowResponse(
        r.groupKey(),
        r.sales(),
        r.grossAmount(),
        r.discountAmount(),
        r.averageBasket(),
        r.discountRate());
  }
}
