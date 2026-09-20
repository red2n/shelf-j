package com.storeql.pricing.messaging;

import com.storeql.pricing.service.AppliedPriceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Works the price-evaluation queue (03.12): every change that can move a price, and every scheduled
 * start or end of a promotion or price list, becomes a row in the applied-price ledger within a few
 * seconds. On start it also opens the ledger for any tenant that has prices and no history yet, so
 * its prior prices begin from today rather than never.
 */
@ApplicationScoped
public class AppliedPriceSweeper {

  private static final Logger LOG = System.getLogger(AppliedPriceSweeper.class.getName());

  @Inject AppliedPriceService service;

  @Inject
  @ConfigProperty(name = "storeql.pricing.applied-price-sweeper.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(
      name = "storeql.pricing.applied-price-sweeper.interval-seconds",
      defaultValue = "5")
  long intervalSeconds;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!enabled) {
      LOG.log(Level.INFO, "Applied-price sweeper disabled");
      return;
    }
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "pricing-applied-price-sweeper");
              t.setDaemon(true);
              return t;
            });
    scheduler.schedule(this::openQuietly, intervalSeconds, TimeUnit.SECONDS);
    scheduler.scheduleWithFixedDelay(
        this::sweepQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "Applied-price sweeper started (every {0}s)", intervalSeconds);
  }

  private void openQuietly() {
    try {
      int opened = service.openLedgers();
      if (opened > 0) LOG.log(Level.INFO, "Applied-price ledger opened for {0} tenant(s)", opened);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Applied-price ledger opening deferred: " + e.getMessage());
    }
  }

  private void sweepQuietly() {
    try {
      // Keep working while anything was done: a variant's next evaluation is claimable only once
      // the
      // one before it is done, so even a batch that was not full can leave more due at once.
      int done;
      do {
        done = service.processDue();
      } while (done > 0);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Applied-price sweep deferred: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
