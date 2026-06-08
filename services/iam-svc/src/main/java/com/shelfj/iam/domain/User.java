package com.shelfj.iam.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A user — STAFF (belongs to a tenant) or CUSTOMER (tenantId null/global). See V1__init.sql for the
 * tenant note.
 */
public record User(
    UUID id,
    UUID tenantId, // null for CUSTOMER
    String type, // STAFF | CUSTOMER
    String email,
    String phone,
    String passwordHash,
    String status, // ACTIVE | DISABLED
    Instant createdAt,
    Instant updatedAt) {
  public static final String TYPE_STAFF = "STAFF";
  public static final String TYPE_CUSTOMER = "CUSTOMER";
  public static final String STATUS_ACTIVE = "ACTIVE";
}
