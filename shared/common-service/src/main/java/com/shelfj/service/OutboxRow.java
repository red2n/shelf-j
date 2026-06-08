package com.shelfj.service;

import java.util.UUID;

/**
 * A transactional outbox row written inside the same DB transaction as a state change (golden rule
 * #6). Moved here from each repo's inner record so the service layer can build rows without
 * importing a repo type.
 */
public record OutboxRow(
    String eventType, String topic, UUID tenantId, UUID aggregateId, String payload) {}
