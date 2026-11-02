package com.gen3.shared.readhistory.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.shared.readhistory.port.ReadHistoryRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReadHistoryConfigurationTest {
  @Test
  void recommendationApplicationExplicitlyRegistersSharedHistoryConfiguration() {
    var wiring =
        com.gen3.recommenderagent.RecommenderAgentApplication.class.getAnnotation(
            org.springframework.context.annotation.Import.class);
    assertThat(wiring).isNotNull();
    assertThat(wiring.value()).contains(ReadHistoryConfiguration.class);
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ReadHistoryConfiguration.class);

  @Test
  void disabledHistoryCreatesNoConnectionsOrRepository() {
    runner.run(
        context ->
            assertThat(context)
                .doesNotHaveBean(HikariDataSource.class)
                .doesNotHaveBean(ReadHistoryRepository.class));
  }

  @Test
  void clientRepositoryOverridesPostgresWithoutCreatingItsPool() {
    runner
        .withPropertyValues("read-history.enabled=true")
        .withBean(ReadHistoryRepository.class, () -> (user, limit) -> Set.of("client-book"))
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(ReadHistoryRepository.class)
                    .doesNotHaveBean(HikariDataSource.class));
  }

  @Test
  void enabledAdapterStartsWithoutConnectingAndDoesNotCreateSchemas() {
    runner
        .withPropertyValues(
            "read-history.enabled=true",
            "read-history.jdbc-url=jdbc:postgresql://127.0.0.1:1/unavailable")
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(ReadHistoryRepository.class)
                    .hasSingleBean(HikariDataSource.class));
  }
}
