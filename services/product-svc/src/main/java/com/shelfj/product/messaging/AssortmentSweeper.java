package com.shelfj.product.messaging;

import com.shelfj.product.repo.AssortmentRepository;
import com.shelfj.product.service.AssortmentService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Puts dated range changes into force on the day they said (07.18).
 *
 * <p>Without this the feature is half a promise: a buyer records a change for the first Monday of
 * the month and somebody still has to remember to press a button that morning. The sweep is the
 * other half, and the route stays because a shop that has just fixed a refusal should not have to
 * wait for the next tick.
 *
 * <p>Hourly rather than nightly, and it asks for changes dated <em>on or before</em> today, so a
 * deployment that was down overnight catches up on its next tick instead of leaving a range a day
 * behind. Applying twice is harmless — the change is marked in the same transaction as the range it
 * moved — which is what makes a catch-up run safe.
 *
 * <p>It reads which businesses have work rather than being told, because a sweeper that had to be
 * given a tenant list would quietly stop covering a business added after it was written.
 */
@ApplicationScoped
public class AssortmentSweeper {

  private static final Logger LOG = System.getLogger(AssortmentSweeper.class.getName());

  @Inject AssortmentService service;
  @Inject AssortmentRepository repo;

  @Inject
  @ConfigProperty(name = "shelfj.product.assortment-sweeper.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "shelfj.product.assortment-sweeper.minutes", defaultValue = "60")
  long minutes;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager CDI startup */
  }

  @PostConstruct
  void start() {
    if (!enabled) {
      LOG.log(Level.INFO, "Assortment sweeper disabled");
      return;
    }
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "product-assortment-sweeper");
              t.setDaemon(true);
              return t;
            });
    long seconds = Math.max(1L, minutes) * 60L;
    scheduler.scheduleWithFixedDelay(this::sweepQuietly, seconds, seconds, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "Assortment sweeper started (every {0} minutes)", minutes);
  }

  /**
   * One tick, and the number of changes it put into force.
   *
   * <p>A business whose changes cannot be applied is logged and the rest are still swept: one
   * shop's empty cluster must not hold back every other shop's range. Public so an integration test
   * can drive a tick rather than wait an hour for one.
   */
  public int sweepQuietly() {
    int applied = 0;
    try {
      LocalDate today = LocalDate.now();
      for (UUID tenantId : repo.tenantsWithDue(today)) {
        try {
          AssortmentService.SweepResult result = service.applyDue(tenantId, today);
          applied += result.applied();
          if (!result.notApplied().isEmpty()) {
            LOG.log(
                Level.WARNING,
                "Range changes not applied for tenant {0}: {1} — {2}",
                tenantId,
                result.notApplied().size(),
                result.notApplied().get(0).detail());
          }
        } catch (RuntimeException e) {
          LOG.log(
              Level.WARNING, "Range sweep deferred for tenant " + tenantId + ": " + e.getMessage());
        }
      }
      if (applied > 0) LOG.log(Level.INFO, "Range changes put into force: {0}", applied);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Range sweep deferred: " + e.getMessage());
    }
    return applied;
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
