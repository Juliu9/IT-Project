package com.gen3.migrationjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MigrationApplicationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(MigrationApplication.class)
          .withPropertyValues(
              "spring.ai.openai.api-key=",
              "solr.url=http://localhost:8983/solr",
              "solr.collection=books",
              "solr.username=",
              "solr.password=")
          .withBean(QdrantClient.class, () -> mock(QdrantClient.class));

  @Test
  void requiresOptIn() {
    runner.run(
        context ->
            assertThat(context).hasFailed().getFailure().hasMessageContaining("Explicitly enable"));
  }

  @Test
  void loadsOnlyMigrationMachinery() {
    runner
        .withPropertyValues("audiobook.qdrant.migration.enabled=true")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(org.springframework.ai.chat.model.ChatModel.class)
                    .doesNotHaveBean(org.springframework.ai.embedding.EmbeddingModel.class)
                    .doesNotHaveBean(org.springframework.ai.openai.OpenAiAudioSpeechModel.class)
                    .hasSingleBean(SolrToQdrantMigrator.class)
                    .hasSingleBean(SolrAudiobookRepository.class)
                    .hasSingleBean(QdrantAudiobookRepository.class)
                    .doesNotHaveBean(com.gen3.recommenderagent.api.RequestGateway.class)
                    .doesNotHaveBean(
                        org.springframework.data.redis.connection.RedisConnectionFactory.class));
  }

  @Test
  void rejectsSolrAsTheMigrationDestinationRatherThanSilentlySwitchingDatabases() {
    runner
        .withPropertyValues("audiobook.qdrant.migration.enabled=true", "audiobook.database=solr")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("Migration requires audiobook.database=qdrant"));
  }

  @Test
  void rejectsSimultaneousJobs() {
    runner
        .withPropertyValues(
            "audiobook.qdrant.migration.enabled=true", "user.preferences.import-on-startup=true")
        .run(
            context ->
                assertThat(context).hasFailed().getFailure().hasMessageContaining("separately"));
  }
}
