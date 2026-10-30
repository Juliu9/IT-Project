package com.gen3.snapshotjob;

import com.gen3.catalogue.snapshot.BookVectorSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;

/** Explicit, read-only catalogue export; no model, recommendation server, or profile writes. */
@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:non-generative-jobs.properties")
@EnableAutoConfiguration(
    exclude = {
      org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration.class
    })
@Import({
  SnapshotConfiguration.class,
  com.gen3.catalogue.solr.SolrConfig.class,
  com.gen3.catalogue.solr.SolrAudiobookRepository.class,
  com.gen3.catalogue.qdrant.QdrantConfig.class
})
public class BookVectorSnapshotApplication {
  public static void main(String[] args) {
    try (var context =
        new SpringApplicationBuilder(BookVectorSnapshotApplication.class)
            .web(WebApplicationType.NONE)
            .run(args)) {
      // Release catalogue clients after publishing the snapshot.
    }
  }

  @Bean
  static BeanFactoryPostProcessor validateJob(Environment environment) {
    return factory -> {
      if (environment.getProperty("user.preferences.import-on-startup", Boolean.class, false)
          || environment.getProperty("audiobook.embedding-index.enabled", Boolean.class, false)
          || environment.getProperty("audiobook.qdrant.migration.enabled", Boolean.class, false)
          || environment.getProperty("audiobook.short-description.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Run standalone snapshot rebuild separately from other maintenance jobs");
      }
    };
  }

  @Bean
  ApplicationRunner snapshotJob(
      BookVectorSnapshot snapshot,
      BookVectorSnapshot.Source source,
      @Value("${book.vector-snapshot.page-size:500}") int pageSize) {
    return args -> snapshot.rebuild(source, pageSize);
  }
}
