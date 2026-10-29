package com.gen3.userpreferencejob;

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
  com.gen3.recommenderagent.storage.user.qdrant.QdrantUserPreferenceRepository.class,
  com.gen3.recommenderagent.storage.user.qdrant.QdrantUserPreferenceMapper.class,
  com.gen3.catalogue.solr.SolrConfig.class,
  com.gen3.catalogue.solr.SolrAudiobookRepository.class,
  com.gen3.recommenderagent.storage.user.solr.SolrUserPreferenceRepository.class,
  com.gen3.userpreferencejob.UserPreferenceStartupImporter.class,
  com.gen3.userpreferencejob.DryRunUserPreferenceRepository.class,
  com.gen3.userpreferencejob.UserPreferenceAggregatorService.class,
  com.gen3.userpreferencejob.UserFavouriteCsvReader.class,
  com.gen3.userpreferencejob.UserHistoryCsvReader.class
})
public class UserPreferenceApplication {
  public static void main(String[] args) {
    try (var context =
        new SpringApplicationBuilder(UserPreferenceApplication.class)
            .web(WebApplicationType.NONE)
            .run(args)) {
      // Close database clients when the maintenance runner finishes.
    }
  }

  @Bean
  static BeanFactoryPostProcessor validateJob(Environment environment) {
    return factory -> {
      String mode = environment.getProperty("user.preferences.import.mode", "dry-run");
      if (!"dry-run".equals(mode) && !"write".equals(mode)) {
        throw new IllegalArgumentException("user.preferences.import.mode must be dry-run or write");
      }
      if (!environment.getProperty("user.preferences.import-on-startup", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Explicitly enable user.preferences.import-on-startup to run this job");
      }
      if (environment.getProperty("audiobook.qdrant.migration.enabled", Boolean.class, false)
          || environment.getProperty("audiobook.embedding-index.enabled", Boolean.class, false)
          || !"disabled"
              .equals(
                  environment.getProperty("audiobook.short-description.batch.mode", "disabled"))) {
        throw new IllegalArgumentException("Run maintenance jobs separately");
      }
    };
  }
}
