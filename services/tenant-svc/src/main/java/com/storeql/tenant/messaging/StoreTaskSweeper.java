package com.storeql.tenant.messaging;

import com.storeql.tenant.service.StoreTaskService;
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
 * Generates every store's day and marks what was missed, hourly by default (store operations &
 * workforce).
 *
 * <p>Hourly, because a store's day starts on its own clock: a sweep once a night in UTC would give
 * a shop in Sydney its list eleven hours late. The first sweep runs soon after start rather than an
 * interval later, since a service that has just restarted must not leave the morning's opening list
 * ungenerated until lunchtime.
 */
@ApplicationScoped
class StoreTaskSweeper {

  private static final Logger LOG = System.getLogger(StoreTaskSweeper.class.getName());

  @Inject StoreTaskService service;

  @Inject
  @ConfigProperty(name = "storeql.tasks.sweeper.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "storeql.tasks.sweeper.interval-seconds", defaultValue = "3600")
  long intervalSeconds;

  @Inject
  @ConfigProperty(name = "storeql.tasks.sweeper.initial-delay-seconds", defaultValue = "30")
  long initialDelaySeconds;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* makes the bean eager */
  }

  @PostConstruct
  void start() {
    if (!enabled) {
      LOG.log(Level.INFO, "store task sweeper disabled");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "store-task-sweeper");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleAtFixedRate(
        service::sweepQuietly, initialDelaySeconds, intervalSeconds, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "store task sweeper every {0}s", intervalSeconds);
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
