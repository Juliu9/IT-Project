package com.gen3.embeddingjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gen3.catalogue.solr.SolrAudiobookRepository;
import com.gen3.embeddingjob.store.AudiobookEmbeddingStore;
import com.gen3.embeddingjob.store.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EmbeddingIndexApplicationTest {
  @Test
  void publishesSnapshotOnlyAfterSuccessfulEmbeddingPass() throws Exception {
    var store = mock(AudiobookEmbeddingStore.class);
    var model = mock(EmbeddingModel.class);
    var snapshot = mock(com.gen3.catalogue.snapshot.BookVectorSnapshot.class);
    var source = mock(com.gen3.catalogue.snapshot.BookVectorSnapshot.Source.class);
    org.mockito.Mockito.when(store.embeddingPage(null, 10, EmbeddingMode.MISSING_ONLY))
        .thenReturn(new AudiobookEmbeddingStore.Page(java.util.List.of(), null));
    new EmbeddingIndexApplication()
        .embeddingJob(store, model, snapshot, source, 500, 10, 0, "missing-only")
        .run(new org.springframework.boot.DefaultApplicationArguments());
    org.mockito.Mockito.verify(snapshot).rebuild(source, 500);
    org.mockito.Mockito.verifyNoInteractions(model);
    org.mockito.Mockito.reset(snapshot);
    org.mockito.Mockito.when(store.embeddingPage(null, 10, EmbeddingMode.MISSING_ONLY))
        .thenThrow(new java.io.IOException("unavailable"));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new EmbeddingIndexApplication()
                    .embeddingJob(store, model, snapshot, source, 500, 10, 0, "missing-only")
                    .run(new org.springframework.boot.DefaultApplicationArguments()))
        .isInstanceOf(java.io.IOException.class);
    org.mockito.Mockito.verifyNoInteractions(snapshot);
  }

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
  void rejectsUnsupportedSnapshotBackends() {
    runner
        .withPropertyValues("audiobook.embedding-index.enabled=true", "audiobook.database=other")
        .withBean(AudiobookEmbeddingStore.class, () -> mock(AudiobookEmbeddingStore.class))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("Error creating bean"));
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"", "all", "MISSING_ONLY", "overwrite"})
  void rejectsUnknownModesBeforeStartingClients(String mode) {
    runner
        .withPropertyValues(
            "audiobook.embedding-index.enabled=true", "audiobook.embedding-index.mode=" + mode)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessage(
                        "audiobook.embedding-index.mode must be missing-only or rebuild-all"));
  }
}
