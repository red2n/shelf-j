package com.storeql.tenant.service;

import static com.storeql.events.EventPayload.esc;

import com.storeql.ids.Ids;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds JSON event payloads for the outbox. Past-tense events, topic storeql.tenant.<event>.
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

  /**
   * A staff role bound at a store. {@code role} is the tier iam-svc binds; {@code roleCode} and
   * {@code permissions} ride beside it when the assignment was made through a custom role (20.10).
   */
  /**
   * What an hour of work cost a store, for a labour report.
   *
   * <p>Carries the <b>store, the day and the money</b> — and deliberately <b>not the person</b>. A
   * labour figure is a fact about a shop's Saturday; who earned what is this service's business and
   * nobody else's, and an event naming both would put pay data in every consumer's database for
   * ever.
   *
   * @param supersedes the entry this correction replaces, so a reader can take the old figure back
   *     out rather than count the day twice
   * @param cost null when no rate was in force on the day, which a reader reports as unknown rather
   *     than as zero: zero is a real rate somebody may be on
   */
  static String labourRecorded(
      UUID tenantId,
      UUID storeId,
      UUID entryId,
      UUID supersedes,
      java.time.LocalDate day,
      long minutes,
      java.math.BigDecimal cost,
      String currency) {
    return """
                {"eventId":"%s","eventType":"LabourRecorded","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "storeId":"%s","day":"%s","minutes":%s,"cost":%s,"currency":%s,"supersedes":%s}"""
        .formatted(
            Ids.newId(),
            tenantId,
            entryId,
            Instant.now(),
            storeId,
            day,
            minutes,
            cost == null ? "null" : cost.toPlainString(),
            currency == null ? "null" : "\"" + esc(currency) + "\"",
            supersedes == null ? "null" : "\"" + supersedes + "\"");
  }

  static String staffAssigned(
      UUID tenantId,
      UUID userId,
      UUID storeId,
      String baseTier,
      String roleCode,
      java.util.Set<String> permissions,
      Instant roleUpdatedAt) {
    String custom =
        roleCode == null
            ? ""
            : ",\"roleCode\":\""
                + esc(roleCode)
                + "\",\"permissions\":"
                + array(permissions)
                + ",\"roleUpdatedAt\":\""
                + roleUpdatedAt
                + "\"";
    return """
                {"eventId":"%s","eventType":"StaffAssigned","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "userId":"%s","storeId":"%s","role":"%s"%s}"""
        .formatted(
            Ids.newId(), tenantId, userId, Instant.now(), userId, storeId, esc(baseTier), custom);
  }

  /** A staff role taken away at a store (SJ-D51): iam-svc unbinds it. */
  static String staffRemoved(UUID tenantId, UUID userId, UUID storeId, String baseTier) {
    return """
                {"eventId":"%s","eventType":"StaffRemoved","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "userId":"%s","storeId":"%s","role":"%s"}"""
        .formatted(Ids.newId(), tenantId, userId, Instant.now(), userId, storeId, esc(baseTier));
  }

  /** A custom role defined or redefined (20.10): iam-svc applies it to the role's holders. */
  static String roleDefined(
      UUID tenantId,
      String code,
      String baseTier,
      java.util.Set<String> permissions,
      Instant updatedAt) {
    // updatedAt is the role's own version: two redefinitions in one second may reach iam-svc in
    // either order, and the older must not overwrite the newer.
    return """
                {"eventId":"%s","eventType":"RoleDefined","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "code":"%s","baseTier":"%s","permissions":%s,"updatedAt":"%s"}"""
        .formatted(
            Ids.newId(),
            tenantId,
            tenantId,
            Instant.now(),
            esc(code),
            esc(baseTier),
            array(permissions),
            updatedAt);
  }

  private static String array(java.util.Set<String> values) {
    return values.stream()
        .sorted()
        .map(v -> "\"" + esc(v) + "\"")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
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

  /** A business's data is due for erasure: every service erases what it holds (21.14). */
  static String tenantDataErasureDue(UUID eventId, UUID tenantId, UUID switchId, String intent) {
    return String.format(
        "{\"eventId\":\"%s\",\"eventType\":\"TenantDataErasureDue\",\"tenantId\":\"%s\","
            + "\"aggregateId\":\"%s\",\"occurredAt\":\"%s\",\"switchId\":\"%s\","
            + "\"intent\":\"%s\"}",
        eventId, tenantId, tenantId, java.time.Instant.now(), switchId, intent);
  }

  /**
   * A task that fell due and was never done (store operations & workforce).
   *
   * <p>Carries the store, the day, what the task was and when it was due — and no person, because
   * nobody did it; that is the point. A missed closing check is what a manager has to hear about.
   */
  static String storeTaskMissed(
      UUID tenantId,
      UUID storeId,
      UUID instanceId,
      String title,
      String kind,
      java.time.LocalDate businessDate,
      Instant dueAt,
      boolean required) {
    return """
                {"eventId":"%s","eventType":"StoreTaskMissed","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "storeId":"%s","title":"%s","kind":"%s","businessDate":"%s","dueAt":"%s","required":%s}"""
        .formatted(
            Ids.newId(),
            tenantId,
            instanceId,
            Instant.now(),
            storeId,
            esc(title),
            esc(kind),
            businessDate,
            dueAt,
            required);
  }

  /**
   * A notice published to a store (store operations & workforce): one per store it reaches, so the
   * store's devices can be told without notification-svc knowing which stores a business has.
   *
   * @param wake whether the devices should be woken now — only an urgent notice is
   */
  static String storeBroadcastPublished(
      UUID tenantId,
      UUID storeId,
      UUID broadcastId,
      String title,
      String priority,
      boolean requiresAck,
      boolean wake) {
    return """
                {"eventId":"%s","eventType":"StoreBroadcastPublished","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "storeId":"%s","title":"%s","priority":"%s","requiresAck":%s,"wake":%s}"""
        .formatted(
            Ids.newId(),
            tenantId,
            broadcastId,
            Instant.now(),
            storeId,
            esc(title),
            esc(priority),
            requiresAck,
            wake);
  }
}
