package com.gen3.embeddingjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gen3.embeddingjob.store.AudiobookEmbeddingStore;
import com.gen3.embeddingjob.store.QdrantEmbeddingStore;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import io.qdrant.client.QdrantClient;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EmbeddingIndexApplicationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(EmbeddingIndexApplication.class)
          .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class))
          .withPropertyValues(
              "spring.ai.openai.api-key=ci-placeholder",
              "spring.ai.model.embedding=none",
              "solr.url=http://localhost:8983/solr",
              "solr.collection=books",
              "solr.username=",
              "solr.password=");

  @Test
  void refusesToRunWithoutExplicitOptIn() {
    runner.run(
        context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasMessage("Explicitly enable audiobook.embedding-index.enabled to run this job"));
  }

  @Test
  void solrSelectionDoesNotCreateQdrantOrRequestHandlingBeans() {
    runner
        .withPropertyValues("audiobook.embedding-index.enabled=true", "audiobook.database=solr")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(AudiobookEmbeddingStore.class)
                  .hasSingleBean(SolrAudiobookRepository.class)
                  .doesNotHaveBean(QdrantClient.class)
                  .doesNotHaveBean(com.gen3.recommenderagent.api.RequestGateway.class)
                  .doesNotHaveBean(
                      org.springframework.data.redis.connection.RedisConnectionFactory.class);
            });
  }

  @Test
  void qdrantSelectionDoesNotCreateSolrBeans() {
    runner
        .withPropertyValues("audiobook.embedding-index.enabled=true", "audiobook.database=qdrant")
        .withBean(QdrantClient.class, () -> mock(QdrantClient.class))
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(AudiobookEmbeddingStore.class)
                    .hasSingleBean(QdrantEmbeddingStore.class)
                    .doesNotHaveBean(SolrClient.class));
  }

  @Test
  void replacementDatabaseCanSupplyItsOwnEmbeddingStoreAdapter() {
    runner
        .withPropertyValues("audiobook.embedding-index.enabled=true", "audiobook.database=other")
        .withBean(AudiobookEmbeddingStore.class, () -> mock(AudiobookEmbeddingStore.class))
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(AudiobookEmbeddingStore.class)
                    .doesNotHaveBean(SolrClient.class)
                    .doesNotHaveBean(QdrantClient.class));
  }

  @Test
  void rejectsSimultaneousMigration() {
    runner
        .withPropertyValues(
            "audiobook.embedding-index.enabled=true", "audiobook.qdrant.migration.enabled=true")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessage("Run embedding indexing separately from migration"));
  }
}
