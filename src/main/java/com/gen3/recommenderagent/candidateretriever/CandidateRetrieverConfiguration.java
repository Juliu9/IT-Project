package com.gen3.recommenderagent.candidateretriever;

import com.gen3.recommenderagent.candidateretriever.retrievalplan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookCandidateSearch;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects one database adapter while keeping retrieval policy independent of the database. */
@Configuration(proxyBeanMethods = false)
public class CandidateRetrieverConfiguration {

  @Bean
  @ConditionalOnProperty(
      name = "audiobook.candidate-retriever",
      havingValue = "qdrant",
      matchIfMissing = true)
  CandidateRetriever qdrantCandidateRetriever(
      @Qualifier("qdrantAudiobookRepository") AudiobookCandidateSearch candidateSearch,
      EmbeddingIndexer embeddingIndexer,
      AudiobookRetrievalPlanner retrievalPlanner) {
    return new CandidateRetrievalService(
        "qdrant", candidateSearch, embeddingIndexer, retrievalPlanner);
  }

  @Bean
  @ConditionalOnProperty(name = "audiobook.candidate-retriever", havingValue = "solr")
  CandidateRetriever solrCandidateRetriever(
      @Qualifier("solrAudiobookRepository") AudiobookCandidateSearch candidateSearch,
      EmbeddingIndexer embeddingIndexer,
      AudiobookRetrievalPlanner retrievalPlanner) {
    return new CandidateRetrievalService(
        "solr", candidateSearch, embeddingIndexer, retrievalPlanner);
  }
}
