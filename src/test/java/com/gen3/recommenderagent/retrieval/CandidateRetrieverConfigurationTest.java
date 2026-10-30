package com.gen3.recommenderagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gen3.catalogue.port.AudiobookCandidateSearch;
import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.catalogue.qdrant.Bm25SparseTextEncoder;
import com.gen3.catalogue.qdrant.QdrantAudiobookRepository;
import com.gen3.catalogue.qdrant.QdrantConfig;
import com.gen3.catalogue.qdrant.QdrantPointMapper;
import com.gen3.catalogue.solr.SolrAudiobookRepository;
import com.gen3.catalogue.solr.SolrConfig;
import com.gen3.recommenderagent.embedding.QueryEmbeddingService;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import com.gen3.recommenderagent.storage.user.qdrant.QdrantUserPreferenceMapper;
import com.gen3.recommenderagent.storage.user.qdrant.QdrantUserPreferenceRepository;
import com.gen3.shortdescriptionjob.store.AudiobookEnrichmentStore;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CandidateRetrieverConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              CandidateRetrieverConfiguration.class,
              QdrantConfig.class,
              QdrantAudiobookRepository.class,
              QdrantPointMapper.class,
              Bm25SparseTextEncoder.class,
              QdrantUserPreferenceMapper.class,
              QdrantUserPreferenceRepository.class,
              SolrConfig.class,
              SolrAudiobookRepository.class,
              com.gen3.recommenderagent.storage.user.solr.SolrUserPreferenceRepository.class)
          .withBean(QueryEmbeddingService.class, () -> mock(QueryEmbeddingService.class))
          .withBean(AudiobookRetrievalPlanner.class, AudiobookRetrievalPlanner::new);

  @Test
  void rejectsShortDescriptionJobOnTheRecommendationServer() {
    runner
        .withPropertyValues("audiobook.short-description.enabled=true")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessage(
                        "Run short-description generation with the dedicated ShortDescriptionApplication entry point"));
  }

  @Test
  void rejectsEmbeddingJobFlagsOnTheRecommendationServer() {
    runner
        .withPropertyValues("audiobook.embedding-index.enabled=true")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessage(
                        "Run embedding indexing with the dedicated EmbeddingIndexApplication entry point"));
  }

  @Test
  void selectsOnlyQdrantByDefault() {
    runner
        .withBean(QdrantClient.class, () -> mock(QdrantClient.class))
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(CandidateRetriever.class)
                  .hasSingleBean(AudiobookCandidateSearch.class)
                  .hasSingleBean(AudiobookVectorRepository.class)
                  .doesNotHaveBean(AudiobookEnrichmentStore.class)
                  .hasSingleBean(UserPreferenceVectorRepository.class)
                  .doesNotHaveBean(SolrConfig.class)
                  .doesNotHaveBean(SolrAudiobookRepository.class);
            });
  }

  private ApplicationContextRunner replacement() {
    return runner
        .withPropertyValues("audiobook.database=other")
        .withBean(AudiobookCandidateSearch.class, () -> mock(AudiobookCandidateSearch.class))
        .withBean(AudiobookVectorRepository.class, () -> mock(AudiobookVectorRepository.class))
        .withBean(
            UserPreferenceVectorRepository.class, () -> mock(UserPreferenceVectorRepository.class));
  }

  @Test
  void completeReplacementNeedsNoQdrantOrSolrConnections() {
    replacement()
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(CandidateRetriever.class)
                    .doesNotHaveBean(QdrantConfig.class)
                    .doesNotHaveBean(QdrantClient.class)
                    .doesNotHaveBean(QdrantAudiobookRepository.class)
                    .doesNotHaveBean(QdrantUserPreferenceRepository.class)
                    .doesNotHaveBean(SolrConfig.class)
                    .doesNotHaveBean(SolrAudiobookRepository.class));
  }

  @Test
  void incompleteReplacementFailsInsteadOfUsingQdrant() {
    runner
        .withPropertyValues("audiobook.database=other")
        .withBean(AudiobookCandidateSearch.class, () -> mock(AudiobookCandidateSearch.class))
        .withBean(AudiobookVectorRepository.class, () -> mock(AudiobookVectorRepository.class))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void selectsOnlySolrWhenConfigured() {
    runner
        .withPropertyValues(
            "audiobook.database=solr",
            "solr.url=http://localhost:8983/solr",
            "solr.collection=books",
            "solr.username=",
            "solr.password=")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(CandidateRetriever.class)
                  .hasSingleBean(AudiobookCandidateSearch.class)
                  .hasSingleBean(AudiobookVectorRepository.class)
                  .hasSingleBean(UserPreferenceVectorRepository.class)
                  .hasSingleBean(
                      com.gen3.recommenderagent.storage.user.solr.SolrUserPreferenceRepository
                          .class)
                  .doesNotHaveBean(QdrantClient.class)
                  .doesNotHaveBean(QdrantConfig.class)
                  .doesNotHaveBean(QdrantAudiobookRepository.class)
                  .doesNotHaveBean(QdrantUserPreferenceRepository.class);
            });
  }

  @Test
  void rejectsMigrationDuringNormalApplicationRun() {
    runner
        .withPropertyValues("audiobook.qdrant.migration.enabled=true")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("dedicated migration run"));
  }

  @Test
  void rejectsImportDuringNormalApplicationRun() {
    runner
        .withPropertyValues("user.preferences.import-on-startup=true")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("dedicated UserPreferenceApplication"));
  }

  @Test
  void rejectsSeparateLegacyDatabaseSelection() {
    runner
        .withPropertyValues("audiobook.candidate-retriever=solr")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("Replace audiobook.candidate-retriever"));
  }
}
