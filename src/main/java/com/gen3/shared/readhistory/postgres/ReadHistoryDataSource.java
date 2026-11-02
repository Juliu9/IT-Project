package com.gen3.shared.readhistory.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.env.Environment;

/** Explicit, small connection pool reused by history adapters; no eager database scan. */
public final class ReadHistoryDataSource {
  private ReadHistoryDataSource() {}

  public static HikariDataSource create(Environment environment) {
    var config = new HikariConfig();
    config.setJdbcUrl(environment.getRequiredProperty("read-history.jdbc-url"));
    config.setUsername(environment.getProperty("read-history.username", ""));
    config.setPassword(environment.getProperty("read-history.password", ""));
    config.setMaximumPoolSize(environment.getProperty("read-history.pool-size", Integer.class, 5));
    config.setMinimumIdle(0);
    config.setConnectionTimeout(2000);
    config.setValidationTimeout(1000);
    config.setInitializationFailTimeout(-1);
    config.addDataSourceProperty("connectTimeout", "2");
    config.addDataSourceProperty("socketTimeout", "10");
    config.addDataSourceProperty("reWriteBatchedInserts", "true");
    config.setPoolName("read-history");
    return new HikariDataSource(config);
  }
}
