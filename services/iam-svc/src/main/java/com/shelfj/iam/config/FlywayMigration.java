package com.shelfj.iam.config;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;

/**
 * Runs Flyway migrations on startup (dev/template convenience). Production runs migrations as a separate
 * run-once Job (README §13.3). Failure here is non-fatal: readiness stays red and retries.
 */
@ApplicationScoped
public class FlywayMigration {

    private static final Logger LOG = System.getLogger(FlywayMigration.class.getName());

    @Inject
    ServiceConfig config;

    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        try {
            var result = Flyway.configure()
                    .dataSource(config.dbUrl(), config.dbUser(), config.dbPassword())
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load()
                    .migrate();
            LOG.log(Level.INFO, "Flyway applied {0} migration(s)", result.migrationsExecuted);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Flyway migration deferred (DB not ready?): " + e.getMessage());
        }
    }
}
