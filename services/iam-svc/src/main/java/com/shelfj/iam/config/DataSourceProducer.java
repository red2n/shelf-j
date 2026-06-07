package com.shelfj.iam.config;

import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.postgresql.ds.PGSimpleDataSource;

/** Produces the application DataSource from config (template baseline: plain Postgres DataSource). */
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
        ds.setCurrentSchema(config.dbSchema());   // database-per-service via a dedicated schema
        return ds;
    }
}
