package com.gen3.readhistoryjob;

import com.gen3.shared.readhistory.port.ReadHistoryWriter;
import com.gen3.shared.readhistory.postgres.PostgresReadHistoryRepository;
import com.gen3.shared.readhistory.postgres.ReadHistoryDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Supplies the standalone importer's default write adapter. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnMissingBean(ReadHistoryWriter.class)
public class PostgresHistoryWriterConfiguration {
  @Bean(destroyMethod = "close")
  HikariDataSource historyImportDataSource(Environment environment) {
    return ReadHistoryDataSource.create(environment);
  }

  @Bean
  ReadHistoryWriter historyImportWriter(
      @Qualifier("historyImportDataSource") HikariDataSource pool) {
    return new PostgresReadHistoryRepository(pool);
  }
}
