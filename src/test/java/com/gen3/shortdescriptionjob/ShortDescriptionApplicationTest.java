package com.gen3.shortdescriptionjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

  @Test
  void requiresExplicitSubmitOrImport() {
    runner.run(
        context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasMessage("Explicitly select short-description batch mode submit or import"));
  }

  @Test
  void selectingSolrLoadsOnlyItsSummaryAdapter() {
    runner
        .withPropertyValues(
            "audiobook.short-description.batch.mode=submit", "audiobook.database=solr")
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
        .withPropertyValues(
            "audiobook.short-description.batch.mode=import", "audiobook.database=qdrant")
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
            "audiobook.short-description.batch.mode=submit",
            "audiobook.embedding-index.enabled=true")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessage(
                        "Run short-description generation separately from indexing and migration"));
  }
}
