package com.shelfj.tenant.mapper;

import com.shelfj.tenant.domain.Broadcasts.Broadcast;
import com.shelfj.tenant.domain.Broadcasts.Reach;
import com.shelfj.tenant.dto.BroadcastDtos;
import java.time.Instant;
import java.util.UUID;

/** Notices on the wire. */
public final class BroadcastMappers {

  private BroadcastMappers() {}

  public static BroadcastDtos.BroadcastResponse toDto(Broadcast b, Instant acknowledgedAt) {
    return new BroadcastDtos.BroadcastResponse(
        b.id().toString(),
        b.title(),
        b.body(),
        b.priority(),
        text(b.storeId()),
        b.role(),
        b.requiresAck(),
        b.publishedAt().toString(),
        text(b.expiresAt()),
        b.status(),
        text(b.createdBy()),
        text(b.withdrawnAt()),
        text(b.withdrawnBy()),
        b.withdrawnReason(),
        text(acknowledgedAt));
  }

  public static BroadcastDtos.ReachResponse toDto(Reach r) {
    return new BroadcastDtos.ReachResponse(
        r.storeId().toString(),
        r.addressed(),
        r.acknowledged(),
        r.outstanding().stream().map(UUID::toString).toList(),
        r.complete());
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }

  private static String text(Instant at) {
    return at == null ? null : at.toString();
  }
}
