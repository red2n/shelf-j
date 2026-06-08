package com.shelfj.tenant.service;

import java.time.Instant;
import java.util.UUID;

/** Builds JSON event payloads for the outbox. Past-tense events, topic shelfj.tenant.<event>. */
final class Events {

  private Events() {}

  static String tenantCreated(
      UUID tenantId, UUID ownerUserId, String name, String country, String currency) {
    return """
                {"eventId":"%s","eventType":"TenantCreated","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "ownerUserId":"%s","name":"%s","country":"%s","currency":"%s"}"""
        .formatted(
            UUID.randomUUID(),
            tenantId,
            tenantId,
            Instant.now(),
            ownerUserId,
            name,
            country,
            currency);
  }

  static String storeCreated(
      UUID tenantId, UUID storeId, String code, String type, boolean isDefault) {
    return """
                {"eventId":"%s","eventType":"StoreCreated","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "code":"%s","type":"%s","isDefault":%s}"""
        .formatted(UUID.randomUUID(), tenantId, storeId, Instant.now(), code, type, isDefault);
  }

  static String zoneCreated(UUID tenantId, UUID storeId, UUID zoneId, String code, String type) {
    return """
                {"eventId":"%s","eventType":"ZoneCreated","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "storeId":"%s","code":"%s","type":"%s"}"""
        .formatted(UUID.randomUUID(), tenantId, zoneId, Instant.now(), storeId, code, type);
  }

  static String staffAssigned(UUID tenantId, UUID userId, UUID storeId, String role) {
    return """
                {"eventId":"%s","eventType":"StaffAssigned","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "userId":"%s","storeId":"%s","role":"%s"}"""
        .formatted(UUID.randomUUID(), tenantId, userId, Instant.now(), userId, storeId, role);
  }
}
