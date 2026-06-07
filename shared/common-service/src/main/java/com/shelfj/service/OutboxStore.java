package com.shelfj.service;

import java.util.List;
import java.util.UUID;

/**
 * What the shared {@link OutboxPublisher} needs from a service's repository: read unpublished rows and mark them
 * published. The service's repo implements this (it already has these queries). Keeps the publisher generic.
 */
public interface OutboxStore {

    List<PendingOutbox> pendingOutbox(int limit);

    void markPublished(UUID id);

    /** A pending outbox row: where to publish ({@code topic}) and what ({@code payload}). */
    record PendingOutbox(UUID id, String topic, String payload) {}
}
