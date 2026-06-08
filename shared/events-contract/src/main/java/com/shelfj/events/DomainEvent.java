package com.shelfj.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Base shape every Shelf-J domain event carries.
 *
 * <p>Events are <strong>past-tense facts</strong> ({@code OrderPlaced}, {@code StockReceived})
 * published to Kafka via the transactional outbox. Every event is tenant-scoped and idempotently
 * consumable: consumers dedupe on {@link #eventId()}.
 *
 * <p>This module is CONTRACTS ONLY — no business logic. See README §10 and the {@code add-event}
 * skill.
 */
public interface DomainEvent {

  /** Globally-unique id for this event instance. Consumers dedupe on this to stay idempotent. */
  UUID eventId();

  /** Event type name, PascalCase past tense, e.g. {@code "OrderPlaced"}. */
  String eventType();

  /** Tenant this event belongs to. Consumers scope their effects to this tenant. */
  UUID tenantId();

  /** Id of the aggregate the event is about (e.g. orderId, storeId). */
  UUID aggregateId();

  /** When the fact occurred (UTC). */
  Instant occurredAt();

  /** Schema version of this event type; bump only for non-additive changes. */
  default int schemaVersion() {
    return 1;
  }
}
