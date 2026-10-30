package com.gen3.shortdescriptionjob;

import com.gen3.catalogue.qdrant.Bm25SparseTextEncoder;
import com.gen3.catalogue.qdrant.QdrantConfig;
import com.gen3.catalogue.qdrant.QdrantPointMapper;
import com.gen3.catalogue.solr.SolrAudiobookRepository;
import com.gen3.catalogue.solr.SolrConfig;
import com.gen3.shortdescriptionjob.provider.OpenAiShortDescriptionGenerator;
import com.gen3.shortdescriptionjob.store.QdrantShortDescriptionStore;
import com.gen3.shortdescriptionjob.store.SolrShortDescriptionStore;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;

/** Explicit, separate process for filling or rebuilding short descriptions. */
@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:non-generative-jobs.properties")
@ImportAutoConfiguration(OpenAiShortDescriptionGenerator.ConfigurationAdapter.class)
@EnableAutoConfiguration(
    exclude = {
      org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration.class
    })
@Import({
  ShortDescriptionEnricher.class,
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
      if (!environment.getProperty("audiobook.short-description.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Explicitly enable audiobook.short-description.enabled to run this job");
      }
      ShortDescriptionMode.parse(
          environment.getProperty("audiobook.short-description.mode", "missing-only"));
      if (environment.getProperty("audiobook.qdrant.migration.enabled", Boolean.class, false)
          || environment.getProperty("audiobook.embedding-index.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Run short-description generation separately from indexing and migration");
      }
    };
  }
}
