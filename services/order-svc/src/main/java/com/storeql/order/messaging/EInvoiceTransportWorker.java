package com.storeql.order.messaging;

import com.storeql.order.service.EInvoiceTransportService;
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
 * Sends the e-invoices that are due: queued when issued, tried again after a failed connection,
 * asked after when the network took one and has not answered. Every few seconds; two instances
 * share the queue under SKIP LOCKED.
 */
@ApplicationScoped
public class EInvoiceTransportWorker {

  private static final Logger LOG = System.getLogger(EInvoiceTransportWorker.class.getName());

  static final int BATCH = 50;

  @Inject EInvoiceTransportService service;

  @Inject
  @ConfigProperty(name = "storeql.order.einvoice-transport.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "storeql.order.einvoice-transport.interval-seconds", defaultValue = "15")
  long intervalSeconds;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!enabled) {
      LOG.log(Level.INFO, "E-invoice transport worker disabled");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "order-einvoice-transport");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::deliverQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "E-invoice transport worker started (every {0}s)", intervalSeconds);
  }

  private void deliverQuietly() {
    try {
      int tried = service.deliverDue(BATCH);
      if (tried > 0) LOG.log(Level.INFO, "E-invoice transport tried {0} document(s)", tried);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "E-invoice transport deferred: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
