package com.gen3.readhistoryjob;

import com.gen3.shared.csv.UserHistoryCsvReader;
import com.gen3.shared.readhistory.port.ReadHistoryWriter;
import java.nio.file.Path;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ComponentScan("com.gen3.readhistoryjob.writer")
@ImportAutoConfiguration(PostgresHistoryWriterConfiguration.class)
@PropertySource("classpath:non-generative-jobs.properties")
@EnableAutoConfiguration(
    exclude = {
      org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration.class,
      org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration.class
    })
public class ReadHistoryApplication {
  /** Starts this application with the supplied command-line configuration. */
  public static void main(String[] args) {
    try (var context =
        new SpringApplicationBuilder(ReadHistoryApplication.class)
            .web(WebApplicationType.NONE)
            .properties(com.gen3.shared.jobruntime.MaintenanceLogFile.property("read-history"))
            .run(args)) {
      // Release the dedicated history pool after the import finishes.
    }
  }

  /** Rejects incompatible job flags and missing opt-in settings before job execution. */
  @Bean
  static BeanFactoryPostProcessor validateJob(Environment environment) {
    return factory -> {
      if (!environment.getProperty("read-history.import.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException(
            "Explicitly enable read-history.import.enabled to import history");
      }
      if (environment.getProperty("user.preferences.import-on-startup", Boolean.class, false)
          || environment.getProperty("audiobook.embedding-index.enabled", Boolean.class, false)
          || environment.getProperty("audiobook.qdrant.migration.enabled", Boolean.class, false)
          || environment.getProperty("audiobook.short-description.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException("Run maintenance jobs separately");
      }
      if (environment.getProperty("read-history.import.csv-path", "").isBlank()) {
        throw new IllegalArgumentException("read-history.import.csv-path is required");
      }
    };
  }

  /** Creates the history importer using the configured CSV, batch size, and selected writer. */
  @Bean
  ApplicationRunner readHistoryImport(Environment environment, ReadHistoryWriter writer) {
    return args ->
        new ReadHistoryImporter(new UserHistoryCsvReader(), writer)
            .run(
                Path.of(environment.getRequiredProperty("read-history.import.csv-path")),
                environment.getProperty("read-history.import.batch-size", Integer.class, 1000));
  }
}
