package com.gen3.shared.readhistory.postgres;

import com.gen3.shared.readhistory.port.ReadHistoryRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "read-history.enabled", havingValue = "true")
@ConditionalOnMissingBean(ReadHistoryRepository.class)
public class ReadHistoryConfiguration {
  /** Creates the explicitly configured connection pool for optional read-history access. */
  @Bean(destroyMethod = "close")
  HikariDataSource readHistoryDataSource(Environment environment) {
    return ReadHistoryDataSource.create(environment);
  }

  /** Creates the PostgreSQL history adapter using the dedicated history connection pool. */
  @Bean
  ReadHistoryRepository readHistoryRepository(
      @org.springframework.beans.factory.annotation.Qualifier("readHistoryDataSource")
          HikariDataSource readHistoryDataSource) {
    return new PostgresReadHistoryRepository(readHistoryDataSource);
  }
}
