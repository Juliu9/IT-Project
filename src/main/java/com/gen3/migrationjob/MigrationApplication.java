package com.gen3.migrationjob;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:non-generative-jobs.properties")
@EnableAutoConfiguration(
    exclude = {
      org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration.class
    })
@Import({
  com.gen3.catalogue.qdrant.QdrantConfig.class,
  com.gen3.catalogue.qdrant.QdrantPointMapper.class,
  com.gen3.catalogue.qdrant.Bm25SparseTextEncoder.class,
  com.gen3.catalogue.qdrant.QdrantAudiobookRepository.class,
  com.gen3.catalogue.solr.SolrConfig.class,
  com.gen3.catalogue.solr.SolrAudiobookRepository.class,
  com.gen3.migrationjob.SolrToQdrantMigrator.class
})
public class MigrationApplication {
  public static void main(String[] args) {
    try (var context =
        new SpringApplicationBuilder(MigrationApplication.class)
            .web(WebApplicationType.NONE)
            .run(args)) {
      // Close database clients when the maintenance runner finishes.
    }
  }

  @Bean
  static BeanFactoryPostProcessor validateJob(Environment environment) {
    return factory -> {
      if (!environment.getProperty("audiobook.qdrant.migration.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Explicitly enable audiobook.qdrant.migration.enabled to run this job");
      }
      if (environment.getProperty("user.preferences.import-on-startup", Boolean.class, false)
          || environment.getProperty("audiobook.embedding-index.enabled", Boolean.class, false)
          || environment.getProperty("audiobook.short-description.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException("Run maintenance jobs separately");
      }
      if (!"qdrant".equals(environment.getProperty("audiobook.database", "qdrant"))) {
        throw new IllegalArgumentException("Migration requires audiobook.database=qdrant");
      }
    };
  }
}
