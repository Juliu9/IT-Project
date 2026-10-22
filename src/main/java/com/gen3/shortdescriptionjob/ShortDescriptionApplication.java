package com.gen3.shortdescriptionjob;

import com.gen3.recommenderagent.storage.audiobook.qdrant.Bm25SparseTextEncoder;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantConfig;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantPointMapper;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrConfig;
import com.gen3.shortdescriptionjob.provider.OpenAiShortDescriptionBatchProvider;
import com.gen3.shortdescriptionjob.store.QdrantShortDescriptionStore;
import com.gen3.shortdescriptionjob.store.SolrShortDescriptionStore;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** Explicit, separate process for submitting or importing short-description batches. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    exclude = {
      org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration.class
    })
@Import({
  BatchShortDescriptionEnricher.class,
  OpenAiShortDescriptionBatchProvider.class,
  QdrantShortDescriptionStore.class,
  SolrShortDescriptionStore.class,
  QdrantConfig.class,
  QdrantPointMapper.class,
  Bm25SparseTextEncoder.class,
  SolrConfig.class,
  SolrAudiobookRepository.class
})
public class ShortDescriptionApplication {
  public static void main(String[] args) {
    try (var context =
        new SpringApplicationBuilder(ShortDescriptionApplication.class)
            .web(WebApplicationType.NONE)
            .properties("spring.ai.model.embedding=none", "spring.ai.model.chat=none")
            .run(args)) {
      // Release database clients after the maintenance runner completes.
    }
  }

  @Bean
  static BeanFactoryPostProcessor validateJob(Environment environment) {
    return factory -> {
      if (environment.getProperty("user.preferences.import-on-startup", Boolean.class, false)) {
        throw new IllegalArgumentException("Run user-preference import separately");
      }
      String mode = environment.getProperty("audiobook.short-description.batch.mode", "disabled");
      if (!"submit".equals(mode) && !"import".equals(mode)) {
        throw new IllegalArgumentException(
            "Explicitly select short-description batch mode submit or import");
      }
      if (environment.getProperty("audiobook.qdrant.migration.enabled", Boolean.class, false)
          || environment.getProperty("audiobook.embedding-index.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Run short-description generation separately from indexing and migration");
      }
    };
  }
}
