package com.shelfj.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Produces the application {@link DataSource} from {@link ServiceSettings}, scoped to the service's
 * own schema (database-per-service). Shared so each service no longer hand-writes this.
 */
@ApplicationScoped
public class DataSourceProducer {

  @Inject ServiceSettings settings;

  @Produces
  @ApplicationScoped
  public DataSource dataSource() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(settings.dbUrl());
    ds.setUser(settings.dbUser());
    ds.setPassword(settings.dbPassword());
    ds.setCurrentSchema(settings.dbSchema());
    return ds;
  }
}
