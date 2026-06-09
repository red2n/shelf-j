package com.shelfj.order.mapper;

import com.shelfj.order.domain.Domain.GiftCard;
import com.shelfj.order.domain.Domain.GiftCardTransaction;
import com.shelfj.order.domain.Domain.Layaway;
import com.shelfj.order.domain.Domain.LayawayDeposit;
import com.shelfj.order.domain.Domain.LayawayItem;
import com.shelfj.order.domain.Domain.Order;
import com.shelfj.order.domain.Domain.OrderItem;
import com.shelfj.order.domain.Domain.OrderStatusHistory;
import com.shelfj.order.domain.Domain.PosVoidLog;
import com.shelfj.order.domain.Domain.Return;
import com.shelfj.order.domain.Domain.ReturnItem;
import com.shelfj.order.dto.Dtos.GiftCardResponse;
import com.shelfj.order.dto.Dtos.GiftCardTransactionResponse;
import com.shelfj.order.dto.Dtos.LayawayDepositResponse;
import com.shelfj.order.dto.Dtos.LayawayItemResponse;
import com.shelfj.order.dto.Dtos.LayawayResponse;
import com.shelfj.order.dto.Dtos.OrderItemResponse;
import com.shelfj.order.dto.Dtos.OrderResponse;
import com.shelfj.order.dto.Dtos.OrderStatusHistoryResponse;
import com.shelfj.order.dto.Dtos.ReturnItemResponse;
import com.shelfj.order.dto.Dtos.ReturnResponse;
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
        o.total(),
        o.currency(),
        o.notes(),
        ts(o.createdAt()),
        ts(o.updatedAt()),
        items.stream().map(Mappers::toDto).toList());
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

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }
}
