package com.shelfj.tenant.service;

import static com.shelfj.events.EventPayload.esc;

import com.shelfj.ids.Ids;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds JSON event payloads for the outbox. Past-tense events, topic shelfj.tenant.<event>.
 *
 * <p>Every interpolated string that originates from a request (tenant name, store/zone codes,
 * country, currency, role) goes through {@code esc()} — a quote in a tenant name must not be able
 * to corrupt the event JSON or inject fields into it.
 */
final class Events {

  private Events() {}

  static String tenantCreated(
      UUID tenantId, UUID ownerUserId, String name, String country, String currency) {
    return """
                {"eventId":"%s","eventType":"TenantCreated","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "ownerUserId":"%s","name":"%s","country":"%s","currency":"%s"}"""
        .formatted(
            Ids.newId(),
            tenantId,
            tenantId,
            Instant.now(),
            ownerUserId,
            esc(name),
            esc(country),
            esc(currency));
  }

  /**
   * Re-announces a tenant's declared trading currency.
   *
   * <p>Separate from {@link #tenantCreated} on purpose. The currency is also carried on
   * TenantCreated, but that event is consumed by iam-svc to provision the owner's user record, so
   * replaying it to fix a currency projection would re-run unrelated onboarding work — the
   * consumer-side dedupe is keyed on eventId, and a replay necessarily carries a fresh one. This
   * event states one fact and nothing else, so it is safe to emit as often as needed.
   */
  static String tenantCurrencyDeclared(UUID tenantId, String currency) {
    return """
                {"eventId":"%s","eventType":"TenantCurrencyDeclared","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "currency":"%s"}"""
        .formatted(Ids.newId(), tenantId, tenantId, Instant.now(), esc(currency));
  }

  static String storeCreated(
      UUID tenantId, UUID storeId, String code, String type, boolean isDefault) {
    return """
                {"eventId":"%s","eventType":"StoreCreated","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "code":"%s","type":"%s","isDefault":%s}"""
        .formatted(Ids.newId(), tenantId, storeId, Instant.now(), esc(code), esc(type), isDefault);
  }

  static String zoneCreated(UUID tenantId, UUID storeId, UUID zoneId, String code, String type) {
    return """
                {"eventId":"%s","eventType":"ZoneCreated","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "storeId":"%s","code":"%s","type":"%s"}"""
        .formatted(Ids.newId(), tenantId, zoneId, Instant.now(), storeId, esc(code), esc(type));
  }

  static String staffAssigned(UUID tenantId, UUID userId, UUID storeId, String role) {
    return """
                {"eventId":"%s","eventType":"StaffAssigned","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "userId":"%s","storeId":"%s","role":"%s"}"""
        .formatted(Ids.newId(), tenantId, userId, Instant.now(), userId, storeId, esc(role));
  }

  static String tenantStatusChanged(UUID tenantId, String status) {
    return """
                {"eventId":"%s","eventType":"TenantStatusChanged","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "status":"%s"}"""
        .formatted(Ids.newId(), tenantId, tenantId, Instant.now(), esc(status));
  }

  static String storeStatusChanged(UUID tenantId, UUID storeId, String status) {
    return """
                {"eventId":"%s","eventType":"StoreStatusChanged","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "storeId":"%s","status":"%s"}"""
        .formatted(Ids.newId(), tenantId, storeId, Instant.now(), storeId, esc(status));
  }

  static String userRoleGranted(UUID tenantId, UUID userId, String role) {
    return """
                {"eventId":"%s","eventType":"UserRoleGranted","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "userId":"%s","role":"%s"}"""
        .formatted(Ids.newId(), tenantId, userId, Instant.now(), userId, esc(role));
  }
}
