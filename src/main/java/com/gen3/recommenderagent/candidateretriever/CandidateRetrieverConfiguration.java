package com.gen3.recommenderagent.candidateretriever;

import com.gen3.recommenderagent.candidateretriever.retrievalplan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookCandidateSearch;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookVectorRepository;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Requires one complete database adapter set without naming database implementations. */
@Configuration(proxyBeanMethods = false)
public class CandidateRetrieverConfiguration {

  @Bean
  static BeanFactoryPostProcessor validateDatabaseSelection(Environment environment) {
    return factory -> {
      String backend = environment.getProperty("audiobook.database", "qdrant");
      if (backend.isBlank() || "solr".equals(backend)) {
        throw new IllegalArgumentException(
            "audiobook.database must select a complete database adapter set. Solr lacks vector lookup, enrichment, and user-preference adapters.");
      }
      if (environment.containsProperty("audiobook.candidate-retriever")) {
        throw new IllegalArgumentException(
            "Replace audiobook.candidate-retriever with audiobook.database; separate database selections are no longer supported.");
      }
      if (environment.getProperty("audiobook.qdrant.migration.enabled", Boolean.class, false)
          && (!"qdrant".equals(backend)
              || !"none".equals(environment.getProperty("spring.main.web-application-type")))) {
        throw new IllegalArgumentException(
            "Solr-to-Qdrant migration requires audiobook.database=qdrant and spring.main.web-application-type=none for a dedicated migration run.");
      }
    };
  }

  @Bean
  CandidateRetriever candidateRetriever(
      @Value("${audiobook.database:qdrant}") String backend,
      AudiobookCandidateSearch candidateSearch,
      AudiobookVectorRepository vectors,
      AudiobookEnrichmentStore enrichment,
      UserPreferenceVectorRepository preferences,
      EmbeddingIndexer embeddingIndexer,
      AudiobookRetrievalPlanner retrievalPlanner) {
    return new CandidateRetrievalService(
        backend, candidateSearch, embeddingIndexer, retrievalPlanner);
  }
}
