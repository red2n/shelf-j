package com.shelfj.inventory.messaging;

import com.shelfj.inventory.service.InventoryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Background sweeper that releases expired HELD reservations (abandoned carts) so the held stock
 * returns to availability — the industry-standard way inventory systems reclaim stuck holds. Each
 * release emits StockReleased.
 */
@ApplicationScoped
public class ReservationSweeper {

  private static final Logger LOG = System.getLogger(ReservationSweeper.class.getName());

  @Inject InventoryService service;

  @Inject
  @ConfigProperty(name = "shelfj.inventory.sweeper-seconds", defaultValue = "30")
  long sweeperSeconds;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "inventory-reservation-sweeper");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::sweepQuietly, sweeperSeconds, sweeperSeconds, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "Reservation sweeper started (every {0}s)", sweeperSeconds);
  }

  private void sweepQuietly() {
    try {
      for (UUID reservationId : service.expiredReservations(200)) {
        UUID tenantId = service.tenantOfReservation(reservationId);
        if (tenantId != null) {
          service.release(tenantId, reservationId);
          LOG.log(Level.DEBUG, "Swept expired reservation {0}", reservationId);
        }
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Reservation sweep deferred: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
