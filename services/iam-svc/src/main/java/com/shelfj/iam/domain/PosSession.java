package com.shelfj.iam.domain;

import java.time.Instant;
import java.util.UUID;

/** Gap #45 — POS session with idle timeout tracking. */
public record PosSession(
    UUID id,
    UUID tenantId,
    UUID userId,
    UUID storeId,
    Instant startedAt,
    Instant lastActivityAt,
    Instant endedAt,
    int idleTimeoutSeconds,
    String status) {
  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_ENDED = "ENDED";
  public static final String STATUS_EXPIRED = "EXPIRED";
}
