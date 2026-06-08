package com.shelfj.inventory.mapper;

import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.Movement;
import com.shelfj.inventory.domain.Domain.Reservation;
import com.shelfj.inventory.domain.Domain.Threshold;
import com.shelfj.inventory.dto.Dtos.BatchResponse;
import com.shelfj.inventory.dto.Dtos.LevelResponse;
import com.shelfj.inventory.dto.Dtos.MovementResponse;
import com.shelfj.inventory.dto.Dtos.ReservationResponse;
import com.shelfj.inventory.dto.Dtos.ThresholdResponse;
import java.time.Instant;

public final class Mappers {

  private Mappers() {}

  public static LevelResponse toLevel(Level l) {
    return new LevelResponse(
        l.storeId().toString(), l.variantId().toString(), l.onHand(), l.reserved(), l.available());
  }

  public static BatchResponse toBatch(Batch b) {
    return new BatchResponse(
        b.id().toString(),
        b.storeId().toString(),
        b.variantId().toString(),
        b.batchNo(),
        b.receivedQty(),
        b.remainingQty(),
        b.costPrice(),
        b.expiryDate() == null ? null : b.expiryDate().toString(),
        ts(b.createdAt()),
        b.status());
  }

  public static ReservationResponse toReservation(Reservation r) {
    return new ReservationResponse(
        r.id().toString(),
        r.storeId().toString(),
        r.variantId().toString(),
        r.qty(),
        r.orderId() == null ? null : r.orderId().toString(),
        r.status(),
        r.expiresAt() == null ? null : r.expiresAt().toString(),
        ts(r.createdAt()));
  }

  public static MovementResponse toMovement(Movement m) {
    return new MovementResponse(
        m.id().toString(),
        m.storeId().toString(),
        m.variantId().toString(),
        m.batchId() == null ? null : m.batchId().toString(),
        m.type(),
        m.qty(),
        m.refType(),
        m.refId() == null ? null : m.refId().toString(),
        ts(m.createdAt()));
  }

  public static ThresholdResponse toThreshold(Threshold t) {
    return new ThresholdResponse(
        t.id().toString(), t.storeId().toString(), t.variantId().toString(), t.threshold());
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }
}
