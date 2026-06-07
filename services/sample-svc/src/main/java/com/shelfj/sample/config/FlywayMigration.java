package com.shelfj.sample.config;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;

/**
 * Runs Flyway migrations on startup (dev/template convenience).
 *
 * <p><strong>Production note:</strong> per README §13.3, migrations should run as a separate run-once Job/Helm hook,
 * NOT inside the app, to avoid N replicas racing. This in-app runner is for local single-instance dev. Gate it off
 * (e.g. {@code shelfj.db.migrate-on-start=false}) in production and run migrations as a Job.</p>
 */
@ApplicationScoped
public class FlywayMigration {

    private static final Logger LOG = System.getLogger(FlywayMigration.class.getName());

    @Inject
    ServiceConfig config;

    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(config.dbUrl(), config.dbUser(), config.dbPassword())
                    .locations("classpath:db/migration")
                    .schemas(config.dbSchema())
                    .defaultSchema(config.dbSchema())
                    .createSchemas(true)
                    .baselineOnMigrate(true)
                    .load();
            var result = flyway.migrate();
            LOG.log(Level.INFO, "Flyway applied {0} migration(s)", result.migrationsExecuted);
        } catch (Exception e) {
            // Don't crash the service if the DB isn't up yet — readiness stays red and retries (golden rule: start in any order).
            LOG.log(Level.WARNING, "Flyway migration deferred (DB not ready?): " + e.getMessage());
        }
    }
}
