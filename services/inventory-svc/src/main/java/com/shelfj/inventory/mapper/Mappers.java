package com.shelfj.inventory.mapper;

import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.Reservation;
import com.shelfj.inventory.dto.Dtos.BatchResponse;
import com.shelfj.inventory.dto.Dtos.LevelResponse;
import com.shelfj.inventory.dto.Dtos.ReservationResponse;

public final class Mappers {

  private Mappers() {}

  public static LevelResponse toLevel(Level l) {
    return new LevelResponse(
        l.storeId().toString(), l.variantId().toString(), l.onHand(), l.reserved(), l.available());
  }

  public static ReservationResponse toReservation(Reservation r) {
    return new ReservationResponse(
        r.id().toString(),
        r.storeId().toString(),
        r.variantId().toString(),
        r.qty(),
        r.status(),
        r.expiresAt() == null ? null : r.expiresAt().toString());
  }

  public static BatchResponse toBatch(Batch b) {
    return new BatchResponse(
        b.id().toString(),
        b.storeId().toString(),
        b.variantId().toString(),
        b.receivedQty(),
        b.remainingQty());
  }
}
