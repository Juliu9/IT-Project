package com.gen3.recommenderagent.candidateretriever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gen3.recommenderagent.candidateretriever.retrievalplan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookCandidateSearch;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CandidateRetrieverConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(CandidateRetrieverConfiguration.class)
          .withBean(EmbeddingIndexer.class, () -> mock(EmbeddingIndexer.class))
          .withBean(AudiobookRetrievalPlanner.class, AudiobookRetrievalPlanner::new);

  @Test
  void selectsQdrantByDefault() {
    contextRunner
        .withBean("qdrantAudiobookRepository", AudiobookCandidateSearch.class, () -> mockSearch())
        .run(context -> assertThat(context).hasSingleBean(CandidateRetriever.class));
  }

  @Test
  void selectsSolrWhenConfigured() {
    contextRunner
        .withPropertyValues("audiobook.candidate-retriever=solr")
        .withBean("solrAudiobookRepository", AudiobookCandidateSearch.class, () -> mockSearch())
        .run(context -> assertThat(context).hasSingleBean(CandidateRetriever.class));
  }

  private AudiobookCandidateSearch mockSearch() {
    return mock(AudiobookCandidateSearch.class);
  }
}
