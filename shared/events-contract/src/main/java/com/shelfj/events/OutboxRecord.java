package com.shelfj.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The shared shape of a transactional-outbox row.
 *
 * <p>Producers write one {@code OutboxRecord} <em>in the same DB transaction</em> as the business state change,
 * guaranteeing the event and the data agree. A background drainer publishes unpublished records to Kafka and
 * marks {@link #publishedAt()}. Delivery is at-least-once, so consumers must be idempotent.</p>
 *
 * <p>Each service owns its own {@code outbox} table (database-per-service); this type just standardizes the columns.
 * Suggested DDL:
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
 */
public record OutboxRecord(
        UUID id,
        String eventType,
        String topic,
        UUID tenantId,
        UUID aggregateId,
        String payload,
        Instant createdAt,
        Instant publishedAt
) {
    /** True if this record has not yet been published to Kafka. */
    public boolean isPending() {
        return publishedAt == null;
    }

    /** Kafka topic naming convention: {@code shelfj.<domain>.<event>}. */
    public static String topicFor(String domain, String eventKebab) {
        return "shelfj." + domain + "." + eventKebab;
    }
}
