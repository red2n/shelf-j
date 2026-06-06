package com.shelfj.sample.config;

import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Produces the application {@link DataSource} from config, for CDI injection into repositories and health checks.
 *
 * <p>Phase-0 template uses a simple Postgres DataSource. A real service would use a pooled DataSource
 * (HikariCP) and/or JPA — see the scaffold-service skill.</p>
 */
@ApplicationScoped
public class DataSourceProducer {

    @Inject
    ServiceConfig config;

    @Produces
    @ApplicationScoped
    public DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(config.dbUrl());
        ds.setUser(config.dbUser());
        ds.setPassword(config.dbPassword());
        return ds;
    }
}
