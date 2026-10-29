package com.gen3.embeddingjob;

import com.gen3.catalogue.qdrant.Bm25SparseTextEncoder;
import com.gen3.catalogue.qdrant.QdrantConfig;
import com.gen3.catalogue.qdrant.QdrantPointMapper;
import com.gen3.catalogue.solr.SolrAudiobookRepository;
import com.gen3.catalogue.solr.SolrConfig;
import com.gen3.embeddingjob.store.AudiobookEmbeddingStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** Separate, opt-in process: imports only the selected database and embedding machinery. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    exclude = {
      org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration.class
    })
@Import({
  com.gen3.snapshotjob.SnapshotConfiguration.class,
  SolrConfig.class,
  com.gen3.embeddingjob.store.SolrEmbeddingStore.class,
  com.gen3.embeddingjob.store.QdrantEmbeddingStore.class,
  SolrAudiobookRepository.class,
  QdrantConfig.class,
  QdrantPointMapper.class,
  Bm25SparseTextEncoder.class
})
public class EmbeddingIndexApplication {
  public static void main(String[] args) {
    try (var context =
        new SpringApplicationBuilder(EmbeddingIndexApplication.class)
            .web(WebApplicationType.NONE)
            .run(args)) {
      // Closing the dedicated process releases database clients after the runner completes.
    }
  }

  @Bean
  static BeanFactoryPostProcessor validateJob(Environment environment) {
    return factory -> {
      if (environment.getProperty("user.preferences.import-on-startup", Boolean.class, false)) {
        throw new IllegalArgumentException("Run user-preference import separately");
      }
      if (!environment.getProperty("audiobook.embedding-index.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Explicitly enable audiobook.embedding-index.enabled to run this job");
      }
      if (!"disabled"
          .equals(environment.getProperty("audiobook.short-description.batch.mode", "disabled"))) {
        throw new IllegalArgumentException(
            "Run embedding indexing separately from short-description generation");
      }
      String database = environment.getProperty("audiobook.database", "qdrant");
      if (database.isBlank()) {
        throw new IllegalArgumentException("Select a database adapter for embedding indexing");
      }
      if (environment.getProperty("audiobook.qdrant.migration.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException("Run embedding indexing separately from migration");
      }
    };
  }

  @Bean
  ApplicationRunner embeddingJob(
      AudiobookEmbeddingStore store,
      EmbeddingModel model,
      com.gen3.catalogue.snapshot.BookVectorSnapshot snapshot,
      com.gen3.catalogue.snapshot.BookVectorSnapshot.Source snapshotSource,
      @Value("${book.vector-snapshot.page-size:500}") int snapshotPageSize,
      @Value("${audiobook.embedding-index.page-size:100}") int pageSize,
      @Value("${audiobook.embedding-index.maximum-records:0}") int maximumRecords) {
    return args -> {
      new CatalogueEmbeddingJob(
              store, new AudiobookEmbeddingIndexer(model), pageSize, maximumRecords)
          .run();
      snapshot.rebuild(snapshotSource, snapshotPageSize);
    };
  }
}
