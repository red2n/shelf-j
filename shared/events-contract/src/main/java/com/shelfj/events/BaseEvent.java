package com.shelfj.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Convenience immutable base carrying the common {@link DomainEvent} envelope fields. Concrete
 * events extend this and add their own payload fields.
 *
 * <p>Example:
 *
 * <pre>{@code
 * public final class StoreCreated extends BaseEvent {
 *     private final String storeCode;
 *     private final String type;
 *     // ... constructor passes envelope fields to super(...)
 * }
 * }</pre>
 */
public abstract class BaseEvent implements DomainEvent {

  private final UUID eventId;
  private final String eventType;
  private final UUID tenantId;
  private final UUID aggregateId;
  private final Instant occurredAt;

  protected BaseEvent(
      UUID eventId, String eventType, UUID tenantId, UUID aggregateId, Instant occurredAt) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.tenantId = tenantId;
    this.aggregateId = aggregateId;
    this.occurredAt = occurredAt;
  }

  @Override
  public UUID eventId() {
    return eventId;
  }

  @Override
  public String eventType() {
    return eventType;
  }

  @Override
  public UUID tenantId() {
    return tenantId;
  }

  @Override
  public UUID aggregateId() {
    return aggregateId;
  }

  @Override
  public Instant occurredAt() {
    return occurredAt;
  }
}
