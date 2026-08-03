package com.shelfj.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The shared shape of a transactional-outbox row.
 *
 * <p>Producers write one {@code OutboxRecord} <em>in the same DB transaction</em> as the business
 * state change, guaranteeing the event and the data agree. A background drainer publishes
 * unpublished records to Kafka and marks {@link #publishedAt()}. Delivery is at-least-once, so
 * consumers must be idempotent.
 *
 * <p>Each service owns its own {@code outbox} table (database-per-service); this type just
 * standardizes the columns. Suggested DDL:
 *
 * <pre>{@code
 * CREATE TABLE outbox (
 *   id           UUID PRIMARY KEY,
 *   event_type   TEXT NOT NULL,
 *   topic        TEXT NOT NULL,
 *   tenant_id    UUID NOT NULL,
 *   aggregate_id UUID NOT NULL,
 *   payload      JSONB NOT NULL,
 *   created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
 *   published_at TIMESTAMPTZ
 * );
 * CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
 * }</pre>
 *
 * @param id primary key of the outbox row; also becomes the Kafka record's dedupe/idempotency key
 * @param eventType PascalCase past-tense event type, e.g. {@code "OrderPlaced"}
 * @param topic destination Kafka topic; see {@link #topicFor(String, String)}
 * @param tenantId tenant the event belongs to
 * @param aggregateId id of the aggregate the event is about (e.g. orderId, storeId)
 * @param payload the serialized event JSON, written verbatim to the Kafka record value
 * @param createdAt when the row was inserted (same transaction as the business state change)
 * @param publishedAt when the drainer successfully published this row, or {@code null} if still
 *     pending
 */
public record OutboxRecord(
    UUID id,
    String eventType,
    String topic,
    UUID tenantId,
    UUID aggregateId,
    String payload,
    Instant createdAt,
    Instant publishedAt) {

  /**
   * @return {@code true} if this record has not yet been published to Kafka (i.e. {@link
   *     #publishedAt()} is {@code null})
   */
  public boolean isPending() {
    return publishedAt == null;
  }

  /**
   * Builds a topic name following the platform convention {@code shelfj.<domain>.<event>}.
   *
   * @param domain the business domain, e.g. {@code "order"}
   * @param eventKebab the event name in kebab-case, e.g. {@code "order-placed"}
   * @return the fully-qualified topic name, e.g. {@code "shelfj.order.order-placed"}
   */
  public static String topicFor(String domain, String eventKebab) {
    return "shelfj." + domain + "." + eventKebab;
  }
}
