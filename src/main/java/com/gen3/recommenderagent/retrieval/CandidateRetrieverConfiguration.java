package com.gen3.recommenderagent.retrieval;

import com.gen3.catalogue.port.AudiobookCandidateSearch;
import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.recommenderagent.embedding.QueryEmbeddingService;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner;
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
      if (environment.getProperty("audiobook.embedding-index.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Run embedding indexing with the dedicated EmbeddingIndexApplication entry point");
      }
      if (!"disabled"
          .equals(environment.getProperty("audiobook.short-description.batch.mode", "disabled"))) {
        throw new IllegalArgumentException(
            "Run short-description generation with the dedicated ShortDescriptionApplication entry point");
      }
      String backend = environment.getProperty("audiobook.database", "qdrant");
      if (backend.isBlank()) {
        throw new IllegalArgumentException(
            "audiobook.database must select a complete database adapter set.");
      }
      if (environment.containsProperty("audiobook.candidate-retriever")) {
        throw new IllegalArgumentException(
            "Replace audiobook.candidate-retriever with audiobook.database; separate database selections are no longer supported.");
      }
      if (environment.getProperty("audiobook.qdrant.migration.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Use the dedicated migration run entry point MigrationApplication");
      }
      if (environment.getProperty("user.preferences.import-on-startup", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Use the dedicated UserPreferenceApplication entry point");
      }
    };
  }

  @Bean
  CandidateRetriever candidateRetriever(
      @Value("${audiobook.database:qdrant}") String backend,
      AudiobookCandidateSearch candidateSearch,
      AudiobookVectorRepository vectors,
      UserPreferenceVectorRepository preferences,
      QueryEmbeddingService embeddingIndexer,
      AudiobookRetrievalPlanner retrievalPlanner) {
    return new CandidateRetrievalService(
        backend, candidateSearch, embeddingIndexer, retrievalPlanner);
  }
}
