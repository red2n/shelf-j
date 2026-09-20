package com.storeql.tenant.messaging;

import com.storeql.tenant.service.SwitchingService;
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
 * Starts the erasures that have fallen due (21.14), hourly by default. The first sweep waits one
 * interval, so a service that has just started erases nothing before anyone can stop it.
 */
@ApplicationScoped
class SwitchingSweeper {

  private static final Logger LOG = System.getLogger(SwitchingSweeper.class.getName());

  @Inject SwitchingService service;

  @Inject
  @ConfigProperty(name = "storeql.switching.sweeper.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "storeql.switching.sweeper.interval-seconds", defaultValue = "3600")
  long intervalSeconds;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* makes the bean eager */
  }

  @PostConstruct
  void start() {
    if (!enabled) {
      LOG.log(Level.INFO, "switching sweeper disabled");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "switching-sweeper");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::sweep, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
  }

  void sweep() {
    try {
      int started = service.sweep();
      if (started > 0) LOG.log(Level.INFO, "switching sweeper started {0} erasures", started);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "switching sweep deferred: {0}", e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
