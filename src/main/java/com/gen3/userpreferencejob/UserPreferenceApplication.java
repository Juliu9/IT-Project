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
  com.gen3.shared.audiobookcatalogue.qdrant.QdrantConfig.class,
  com.gen3.recommenderagent.storage.user.qdrant.QdrantUserPreferenceRepository.class,
  com.gen3.recommenderagent.storage.user.qdrant.QdrantUserPreferenceMapper.class,
  com.gen3.shared.audiobookcatalogue.solr.SolrConfig.class,
  com.gen3.recommenderagent.storage.user.solr.SolrUserPreferenceRepository.class,
  com.gen3.userpreferencejob.UserPreferenceStartupImporter.class,
  com.gen3.userpreferencejob.StreamingUserPreferenceImporter.class,
  com.gen3.userpreferencejob.DryRunUserPreferenceRepository.class,
  com.gen3.userpreferencejob.UserPreferenceAggregatorService.class,
  com.gen3.shared.csv.UserFavouriteCsvReader.class,
  com.gen3.shared.csv.UserHistoryCsvReader.class
})
public class UserPreferenceApplication {
  @Bean
  com.gen3.shared.audiobookcatalogue.snapshot.BookVectorSnapshot bookVectorSnapshot(
      Environment environment) {
    return com.gen3.snapshotjob.SnapshotConfiguration.snapshot(environment);
  }

  public static void main(String[] args) {
    try (var context =
        new SpringApplicationBuilder(UserPreferenceApplication.class)
            .web(WebApplicationType.NONE)
            .properties(com.gen3.shared.jobruntime.MaintenanceLogFile.property("user-import"))
            .run(args)) {
      // Close database clients when the maintenance runner finishes.
    }
  }

  @Bean
  static BeanFactoryPostProcessor validateJob(Environment environment) {
    return factory -> {
      if (environment.getProperty("read-history.import.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Run read-history import separately with ReadHistoryApplication");
      }
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
          || environment.getProperty("audiobook.short-description.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException("Run maintenance jobs separately");
      }
    };
  }
}
