package com.shelfj.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks Kafka consumers that failed to start. {@link BaseKafkaConsumer} used to swallow a start
 * failure behind a log line, so the service stayed "ready" forever while silently never processing
 * events. {@link HealthChecks.KafkaConsumerReadiness} reads this registry so a start failure fails
 * the readiness probe instead (golden rule #12: readiness checks DB+Kafka+config).
 */
final class KafkaConsumerRegistry {

  private static final Set<String> failed = ConcurrentHashMap.newKeySet();

  private KafkaConsumerRegistry() {}

  /**
   * @param consumerName the failing consumer's {@code consumerName()}
   */
  static void markFailed(String consumerName) {
    failed.add(consumerName);
  }

  /**
   * Removes {@code consumerName} from the failed set — called both on a successful start and on
   * {@link BaseKafkaConsumer#stop()}, so a shut-down consumer doesn't keep failing readiness.
   *
   * @param consumerName the consumer's {@code consumerName()}
   */
  static void clear(String consumerName) {
    failed.remove(consumerName);
  }

  /**
   * @return a snapshot of every consumer name currently marked failed; empty when all are healthy
   */
  static Set<String> failedConsumers() {
    return Set.copyOf(failed);
  }
}
