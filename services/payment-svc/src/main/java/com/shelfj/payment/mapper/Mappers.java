package com.shelfj.payment.mapper;

import com.shelfj.payment.domain.Domain.PaymentIntent;
import com.shelfj.payment.domain.Domain.PaymentTender;
import com.shelfj.payment.domain.Domain.RefundTender;
import com.shelfj.payment.dto.Dtos.PaymentIntentResponse;
import com.shelfj.payment.dto.Dtos.RefundResponse;
import com.shelfj.payment.dto.Dtos.TenderResponse;

public final class Mappers {

  private Mappers() {}

  public static TenderResponse toDto(PaymentTender t) {
    return new TenderResponse(
        t.id(),
        t.orderId(),
        t.amount(),
        t.method(),
        t.reference(),
        t.status(),
        t.notes(),
        t.createdAt());
  }

  public static RefundResponse toDto(RefundTender r) {
    return new RefundResponse(
        r.id(),
        r.orderId(),
        r.paymentId(),
        r.amount(),
        r.method(),
        r.reference(),
        r.reason(),
        r.createdAt());
  }

  /**
   * @param i the intent
   * @return its API representation
   */
  public static PaymentIntentResponse toDto(PaymentIntent i) {
    return new PaymentIntentResponse(
        i.id().toString(),
        i.orderId().toString(),
        i.provider(),
        i.status(),
        i.amount(),
        i.capturedAmount(),
        i.currency(),
        i.nextActionUrl(),
        i.failureCode(),
        i.failureMessage(),
        i.paymentId() == null ? null : i.paymentId().toString(),
        i.createdAt().toString());
  }
}
