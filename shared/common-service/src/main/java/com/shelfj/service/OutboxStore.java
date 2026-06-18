package com.shelfj.service;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * What the shared {@link OutboxPublisher} needs from a service's repository: atomically claim a
 * batch of unpublished rows and mark delivered. The service's repo implements this (it already has
 * the outbox table). Keeps the publisher generic.
 */
@FunctionalInterface
public interface OutboxStore {

  /**
   * Locks up to {@code limit} unpublished rows ({@code FOR UPDATE SKIP LOCKED} — production runs
   * multiple replicas of every service, so without this every replica's drain would claim and
   * re-publish the same rows), hands them to {@code publish}, and — inside that same transaction —
   * marks published exactly the ids it returns. Rows {@code publish} doesn't report back stay
   * unpublished and are claimable again (by any replica) on the next drain.
   */
  List<UUID> drainAndPublish(int limit, Function<List<PendingOutbox>, List<UUID>> publish);

  /** A pending outbox row: where to publish ({@code topic}) and what ({@code payload}). */
  record PendingOutbox(UUID id, String topic, String payload) {}
}
