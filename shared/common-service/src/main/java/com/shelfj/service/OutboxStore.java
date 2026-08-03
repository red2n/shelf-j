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
   *
   * @param limit max rows to claim in one call
   * @param publish sends the claimed rows and returns the ids that were confirmed delivered
   * @return the ids that were claimed and successfully marked published
   */
  List<UUID> drainAndPublish(int limit, Function<List<PendingOutbox>, List<UUID>> publish);

  /**
   * A pending outbox row: where to publish and what.
   *
   * @param id the outbox row's primary key, used as the Kafka record key
   * @param topic destination Kafka topic
   * @param payload the serialized event JSON, sent verbatim as the Kafka record value
   */
  record PendingOutbox(UUID id, String topic, String payload) {}
}
