package com.shelfj.service;

import java.util.UUID;

/**
 * A transactional outbox row written inside the same DB transaction as a state change (golden rule
 * #6). Moved here from each repo's inner record so the service layer can build rows without
 * importing a repo type.
 *
 * @param eventType PascalCase past-tense event type, e.g. {@code "OrderPlaced"}
 * @param topic destination Kafka topic, e.g. via {@link com.shelfj.events.OutboxRecord#topicFor}
 * @param tenantId tenant the event belongs to
 * @param aggregateId id of the aggregate the event is about (e.g. orderId, storeId)
 * @param payload the serialized event JSON, typically built with {@link
 *     com.shelfj.events.EventPayload#base}
 */
public record OutboxRow(
    String eventType, String topic, UUID tenantId, UUID aggregateId, String payload) {}
