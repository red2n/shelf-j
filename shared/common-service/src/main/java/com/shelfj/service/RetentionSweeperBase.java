package com.shelfj.service;

import com.shelfj.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The scheduled purge every service that keeps a class of data runs (21.16): once a day by default,
 * for each tenant it holds data for, as that tenant's schedule says. A tenant whose schedule cannot
 * be read is skipped and tried next time; nothing is purged on a guess. A subclass names its
 * tenants and runs one tenant's purge; the lifecycle lives here.
 *
 * <p>Configured by {@code shelfj.retention-sweeper.enabled} and {@code
 * shelfj.retention-sweeper.interval-seconds}; the first sweep waits one interval, so a service that
 * has just started does not purge before anyone can stop it.
 */
public abstract class RetentionSweeperBase {

  private static final Logger LOG = System.getLogger(RetentionSweeperBase.class.getName());

  @Inject
  @ConfigProperty(name = "shelfj.retention-sweeper.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "shelfj.retention-sweeper.interval-seconds", defaultValue = "86400")
  long intervalSeconds;

  private ScheduledExecutorService scheduler;

  /** The name in log lines, e.g. {@code order-retention-sweeper}. */
  protected abstract String name();

  /** The tenants this service holds data for. */
  protected abstract List<UUID> tenants();

  /** One tenant's purge, as the schedule says; empty when it has set no period. */
  protected abstract Optional<Retention.Run> purge(UUID tenantId);

  @PostConstruct
  void start() {
    if (!enabled) {
      LOG.log(Level.INFO, "{0} disabled", name());
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, name());
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::sweepQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "{0} started (every {1}s)", name(), intervalSeconds);
  }

  /** Every tenant's purge, one after another; a tenant that fails does not stop the next. */
  public void sweepQuietly() {
    List<UUID> tenants;
    try {
      tenants = tenants();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "{0} deferred: {1}", name(), e.getMessage());
      return;
    }
    for (UUID tenantId : tenants) {
      try {
        purge(tenantId)
            .ifPresent(
                run ->
                    LOG.log(
                        Level.INFO,
                        "{0}: {1} purged {2} for tenant {3}, {4} held",
                        name(),
                        run.dataClass(),
                        run.rowsAffected(),
                        tenantId,
                        run.heldSkipped()));
      } catch (ApiException e) {
        LOG.log(Level.WARNING, "{0} skipped tenant {1}: {2}", name(), tenantId, e.getMessage());
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "{0} failed for tenant {1}: {2}", name(), tenantId, e.getMessage());
      }
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }
}
