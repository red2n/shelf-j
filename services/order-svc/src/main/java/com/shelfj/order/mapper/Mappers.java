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

  /**
   * Converts one order line to its wire form.
   *
   * @param i one order line to convert
   * @return its API representation
   */
  public static OrderItemResponse toDto(OrderItem i) {
    return new OrderItemResponse(
        str(i.id()),
        str(i.variantId()),
        i.qty(),
        i.unitPrice(),
        i.lineTotal(),
        i.notes(),
        str(i.weighingInstrumentId()));
  }

  /**
   * Converts an order and its lines to the full wire form.
   *
   * @param o the order header
   * @param items the order's lines
   * @return its API representation, header and lines together
   */
  public static OrderResponse toDto(Order o, List<OrderItem> items) {
    return new OrderResponse(
        str(o.id()),
        str(o.storeId()),
        str(o.customerId()),
        str(o.loginId()),
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

  /**
   * Converts an order summary to its wire form.
   *
   * @param o the order summary to convert
   * @return its API representation
   */
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

  /**
   * Converts one status transition to its wire form.
   *
   * @param h one status transition to convert
   * @return its API representation
   */
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

  /**
   * Converts one returned line to its wire form.
   *
   * @param ri one returned line to convert
   * @return its API representation
   */
  public static ReturnItemResponse toDto(ReturnItem ri) {
    return new ReturnItemResponse(
        str(ri.id()), str(ri.variantId()), ri.qty(), ri.refundAmount(), ri.condition());
  }

  /**
   * Converts a return and its lines to the full wire form.
   *
   * @param r the return header
   * @param items the returned lines
   * @return its API representation, header and lines together
   */
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

  /**
   * Converts a recorded void to its wire form.
   *
   * @param vl the recorded void to convert
   * @return its API representation
   */
  public static VoidResponse toDto(PosVoidLog vl) {
    return new VoidResponse(str(vl.orderId()), vl.reason(), ts(vl.voidedAt()));
  }

  /**
   * Converts one layaway line to its wire form.
   *
   * @param li one layaway line to convert
   * @return its API representation
   */
  public static LayawayItemResponse toDto(LayawayItem li) {
    return new LayawayItemResponse(
        str(li.id()), str(li.variantId()), li.qty(), li.unitPrice(), li.lineTotal());
  }

  /**
   * Converts one layaway payment to its wire form.
   *
   * @param d one layaway payment to convert
   * @return its API representation
   */
  public static LayawayDepositResponse toDto(LayawayDeposit d) {
    return new LayawayDepositResponse(
        str(d.id()), d.amount(), d.paymentMethod(), d.reference(), ts(d.paidAt()));
  }

  /**
   * Converts a layaway with its goods and payments to the full wire form.
   *
   * @param l the layaway header, carrying the total and outstanding balance
   * @param items the goods set aside
   * @param deposits the payments made so far
   * @return its API representation, header, items and deposits together
   */
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

  /**
   * Converts a gift card to its wire form.
   *
   * @param gc the gift card to convert
   * @return its API representation
   */
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

  /**
   * Converts one gift-card transaction to its wire form.
   *
   * @param tx one gift-card transaction to convert
   * @return its API representation
   */
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

  /**
   * Converts one special-order line to its wire form.
   *
   * @param i one special-order line to convert
   * @return its API representation
   */
  public static SpecialOrderItemResponse toDto(SpecialOrderItem i) {
    return new SpecialOrderItemResponse(
        str(i.id()), str(i.variantId()), i.qty(), i.unitPrice(), i.lineTotal(), i.notes());
  }

  /**
   * Converts a special order and its lines to the full wire form.
   *
   * @param so the special-order header
   * @param items the ordered lines
   * @return its API representation, header and lines together
   */
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

  /**
   * Converts one exception-report row to its wire form.
   *
   * @param r one exception-report row to convert
   * @return its API representation
   */
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

  /**
   * Converts one POSLog entry to its wire form.
   *
   * @param e one POSLog entry to convert
   * @return its API representation
   */
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

  /**
   * Converts one receipt event to its wire form.
   *
   * @param r one receipt event to convert
   * @return its API representation
   */
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

  /**
   * Converts one hourly sales bucket to its wire form.
   *
   * @param r one hourly sales bucket to convert
   * @return its API representation
   */
  public static SalesByHourRowResponse toDto(SalesByHourRow r) {
    return new SalesByHourRowResponse(
        r.hourOfDay(), r.orders(), r.grossAmount(), r.discountAmount(), r.averageBasket());
  }

  /**
   * Converts one staff member's sales to its wire form.
   *
   * @param r one staff member's sales to convert
   * @return its API representation
   */
  public static SalesByStaffRowResponse toDto(SalesByStaffRow r) {
    return new SalesByStaffRowResponse(
        r.groupKey(),
        r.sales(),
        r.grossAmount(),
        r.discountAmount(),
        r.averageBasket(),
        r.discountRate());
  }

  /**
   * Converts one recorded age check to its wire form.
   *
   * @param v the record
   * @return its API representation
   */
  public static com.shelfj.order.dto.Dtos.AgeVerificationResponse toDto(
      com.shelfj.order.domain.Domain.AgeVerification v) {
    return new com.shelfj.order.dto.Dtos.AgeVerificationResponse(
        str(v.id()),
        str(v.storeId()),
        str(v.cashierId()),
        str(v.posSessionId()),
        str(v.variantId()),
        v.category(),
        v.minimumAge(),
        v.country(),
        v.storePolicy(),
        v.outcome(),
        v.reason(),
        v.idType(),
        str(v.orderId()),
        ts(v.checkedAt()));
  }

  /**
   * Converts the period summary to its wire form.
   *
   * @param s the counts
   * @return its API representation
   */
  public static com.shelfj.order.dto.Dtos.AgeVerificationSummaryResponse toDto(
      com.shelfj.order.domain.Domain.AgeVerificationSummary s) {
    return new com.shelfj.order.dto.Dtos.AgeVerificationSummaryResponse(
        s.total(), s.passed(), s.refused(), s.refusedByReason(), s.byCategory());
  }
}
