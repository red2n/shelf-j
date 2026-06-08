package com.shelfj.gateway.filters;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BruteForceProtectionService {

  private final ConcurrentMap<String, FailureState> stateByKey = new ConcurrentHashMap<>();
  private final int maxFailures;
  private final long blockDurationMs;
  private final long expiryDurationMs = Duration.ofMinutes(30).toMillis();

  public BruteForceProtectionService() {
    this(5, Duration.ofMinutes(15));
  }

  public BruteForceProtectionService(int maxFailures, Duration blockDuration) {
    this.maxFailures = Math.max(1, maxFailures);
    this.blockDurationMs = Math.max(1, blockDuration.toMillis());
  }

  public void recordFailure(String key) {
    if (key == null) {
      return;
    }
    FailureState state = stateByKey.computeIfAbsent(key, k -> new FailureState());
    synchronized (state) {
      long now = System.currentTimeMillis();
      if (state.isExpired(now, expiryDurationMs)) {
        state.reset();
      }
      state.failures++;
      state.lastActivityMs = now;
      if (state.failures >= maxFailures) {
        state.blockedUntilMs = now + blockDurationMs;
        state.failures = 0;
      }
    }
  }

  public void recordSuccess(String key) {
    if (key == null) {
      return;
    }
    FailureState state = stateByKey.get(key);
    if (state != null) {
      synchronized (state) {
        state.reset();
      }
    }
  }

  public boolean isBlocked(String key) {
    if (key == null) {
      return false;
    }
    FailureState state = stateByKey.get(key);
    if (state == null) {
      return false;
    }
    synchronized (state) {
      long now = System.currentTimeMillis();
      if (state.isExpired(now, expiryDurationMs)) {
        state.reset();
        return false;
      }
      return now < state.blockedUntilMs;
    }
  }

  private static final class FailureState {
    private int failures;
    private long blockedUntilMs;
    private long lastActivityMs;

    boolean isExpired(long now, long expiryDurationMs) {
      return lastActivityMs > 0 && now - lastActivityMs >= expiryDurationMs;
    }

    void reset() {
      failures = 0;
      blockedUntilMs = 0;
      lastActivityMs = 0;
    }
  }
}
