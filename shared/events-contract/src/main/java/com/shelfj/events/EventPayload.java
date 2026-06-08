package com.shelfj.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared helpers for building JSON event payloads written to the outbox. Centralises the common
 * envelope fields (eventId, eventType, tenantId, aggregateId, occurredAt) so each service's {@code
 * Events} class only adds its own domain fields.
 */
public final class EventPayload {

  private EventPayload() {}

  /**
   * Returns the opening JSON fields for any domain event (without the closing brace). Callers
   * append domain-specific fields then close with {@code "}"}.
   *
   * <pre>{@code
   * return EventPayload.base("OrderPlaced", tenantId, orderId)
   *     + ",\"total\":" + total.toPlainString() + "}";
   * }</pre>
   */
  public static String base(String eventType, UUID tenantId, UUID aggregateId) {
    return "{\"eventId\":\""
        + UUID.randomUUID()
        + "\",\"eventType\":\""
        + eventType
        + "\",\"tenantId\":\""
        + tenantId
        + "\",\"aggregateId\":\""
        + aggregateId
        + "\",\"occurredAt\":\""
        + Instant.now()
        + "\"";
  }

  /**
   * JSON-escapes a string value (backslash and double-quote only). Returns empty string for null.
   */
  public static String esc(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
