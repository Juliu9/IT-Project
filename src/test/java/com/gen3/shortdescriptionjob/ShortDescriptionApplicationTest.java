package com.gen3.shortdescriptionjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gen3.shortdescriptionjob.provider.ShortDescriptionGenerator;
import com.gen3.shortdescriptionjob.store.AudiobookEnrichmentStore;
import com.gen3.shortdescriptionjob.store.QdrantShortDescriptionStore;
import com.gen3.shortdescriptionjob.store.SolrShortDescriptionStore;
import io.qdrant.client.QdrantClient;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ShortDescriptionApplicationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(ShortDescriptionApplication.class)
          .withPropertyValues(
              "spring.ai.model.embedding=none",
              "spring.ai.model.chat=none",
              "spring.ai.openai.api-key=test-key",
              "solr.url=http://localhost:8983/solr",
              "solr.collection=books",
              "solr.username=",
              "solr.password=");

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"", "all", "missing", "REBUILD_ALL"})
  void rejectsInvalidModeBeforeCreatingClients(String mode) {
    runner
        .withPropertyValues(
            "audiobook.short-description.enabled=true", "audiobook.short-description.mode=" + mode)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessage(
                        "audiobook.short-description.mode must be missing-only or rebuild-all"));
  }

  @Test
  void requiresExplicitEnablement() {
    runner.run(
        context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasMessage(
                    "Explicitly enable audiobook.short-description.enabled to run this job"));
  }

  @Test
  void suppliedGeneratorReplacesTheDefaultWithoutCreatingAnApiClient() {
    var generator = mock(ShortDescriptionGenerator.class);
    runner
        .withPropertyValues(
            "audiobook.short-description.enabled=true",
            "audiobook.database=solr",
            "spring.ai.openai.api-key=")
        .withBean(ShortDescriptionGenerator.class, () -> generator)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(ShortDescriptionGenerator.class);
              assertThat(context.getBean(ShortDescriptionGenerator.class)).isSameAs(generator);
            });
  }

  @Test
  void acceptsExplicitGenerationEnablement() {
    runner
        .withPropertyValues("audiobook.short-description.enabled=true", "audiobook.database=solr")
        .run(
            context ->
                assertThat(context).hasNotFailed().hasSingleBean(ShortDescriptionEnricher.class));
  }

  @Test
  void selectingSolrLoadsOnlyItsSummaryAdapter() {
    runner
        .withPropertyValues("audiobook.short-description.enabled=true", "audiobook.database=solr")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(SolrShortDescriptionStore.class)
                    .hasSingleBean(AudiobookEnrichmentStore.class)
                    .doesNotHaveBean(QdrantClient.class)
                    .doesNotHaveBean(com.gen3.recommenderagent.api.RequestGateway.class)
                    .doesNotHaveBean(
                        org.springframework.data.redis.connection.RedisConnectionFactory.class));
  }

  @Test
  void selectingQdrantLoadsOnlyItsSummaryAdapter() {
    runner
        .withPropertyValues("audiobook.short-description.enabled=true", "audiobook.database=qdrant")
        .withBean(QdrantClient.class, () -> mock(QdrantClient.class))
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(QdrantShortDescriptionStore.class)
                    .hasSingleBean(AudiobookEnrichmentStore.class)
                    .doesNotHaveBean(SolrClient.class));
  }

  @Test
  void rejectsOtherMaintenanceModes() {
    runner
        .withPropertyValues(
            "audiobook.short-description.enabled=true", "audiobook.embedding-index.enabled=true")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessage(
                        "Run short-description generation separately from indexing and migration"));
  }
}
