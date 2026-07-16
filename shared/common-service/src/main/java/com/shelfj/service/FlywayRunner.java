package com.shelfj.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import org.flywaydb.core.Flyway;

/**
 * Runs Flyway migrations on startup into the service's own schema. Dev/template convenience;
 * production runs migrations as a separate Job (docs/ARCHITECTURE.md §17). Failure is non-fatal —
 * readiness stays red and retries.
 */
@ApplicationScoped
public class FlywayRunner {

  private static final Logger LOG = System.getLogger(FlywayRunner.class.getName());

  @Inject ServiceSettings settings;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    try {
      var result =
          Flyway.configure()
              .dataSource(settings.dbMigrationUrl(), settings.dbUser(), settings.dbPassword())
              .locations("classpath:db/migration")
              .schemas(settings.dbSchema())
              .defaultSchema(settings.dbSchema())
              .createSchemas(true)
              .baselineOnMigrate(true)
              .load()
              .migrate();
      LOG.log(
          Level.INFO,
          "Flyway applied {0} migration(s) to schema {1}",
          result.migrationsExecuted,
          settings.dbSchema());
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Flyway migration deferred (DB not ready?): " + e.getMessage());
    }
  }
}
