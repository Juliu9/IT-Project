package com.gen3.userpreferencejob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class UserPreferenceApplicationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(UserPreferenceApplication.class)
          .withPropertyValues(
              "spring.ai.openai.api-key=",
              "user.preferences.favourites-csv-path=",
              "user.preferences.history-csv-path=")
          .withBean(QdrantClient.class, () -> mock(QdrantClient.class));

  @Test
  void requiresOptIn() {
    runner.run(
        context ->
            assertThat(context).hasFailed().getFailure().hasMessageContaining("Explicitly enable"));
  }

  @Test
  void loadsOnlyImportMachinery() {
    runner
        .withPropertyValues("user.preferences.import-on-startup=true")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(org.springframework.ai.chat.model.ChatModel.class)
                    .doesNotHaveBean(org.springframework.ai.embedding.EmbeddingModel.class)
                    .doesNotHaveBean(org.springframework.ai.openai.OpenAiAudioSpeechModel.class)
                    .hasSingleBean(UserPreferenceStartupImporter.class)
                    .hasSingleBean(UserPreferenceAggregatorService.class)
                    .hasSingleBean(DryRunUserPreferenceRepository.class)
                    .doesNotHaveBean(com.gen3.recommenderagent.api.RequestGateway.class)
                    .doesNotHaveBean(
                        org.springframework.data.redis.connection.RedisConnectionFactory.class));
  }

  @Test
  void solrImportWiresBothVectorPortsWithoutQdrantOrAi() {
    new ApplicationContextRunner()
        .withUserConfiguration(UserPreferenceApplication.class)
        .withPropertyValues(
            "audiobook.database=solr",
            "user.preferences.import-on-startup=true",
            "user.preferences.import.mode=write",
            "solr.url=http://localhost:8983/solr",
            "solr.collection=books",
            "solr.username=",
            "solr.password=",
            "user.preferences.favourites-csv-path=",
            "user.preferences.history-csv-path=")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(com.gen3.catalogue.port.AudiobookVectorRepository.class)
                    .hasSingleBean(
                        com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository
                            .class)
                    .doesNotHaveBean(QdrantClient.class)
                    .doesNotHaveBean(org.springframework.ai.embedding.EmbeddingModel.class)
                    .doesNotHaveBean(
                        org.springframework.data.redis.connection.RedisConnectionFactory.class));
  }

  @Test
  void rejectsSimultaneousJobs() {
    runner
        .withPropertyValues(
            "user.preferences.import-on-startup=true", "audiobook.embedding-index.enabled=true")
        .run(
            context ->
                assertThat(context).hasFailed().getFailure().hasMessageContaining("separately"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"", "dry-run ", "WRITE", "true", "yes", "typo"})
  void rejectsUnknownImportModesBeforeImport(String mode) {
    runner
        .withPropertyValues("user.preferences.import-on-startup=true")
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new org.springframework.core.env.MapPropertySource(
                            "importMode", java.util.Map.of("user.preferences.import.mode", mode))))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("mode must be dry-run or write"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"qdrant", "solr"})
  void onlyExplicitWriteModeSelectsDatabaseStorage(String backend) {
    runner
        .withPropertyValues(
            "audiobook.database=" + backend,
            "user.preferences.import-on-startup=true",
            "user.preferences.import.mode=write",
            "solr.url=http://localhost:8983/solr",
            "solr.collection=books",
            "solr.username=",
            "solr.password=")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .doesNotHaveBean(DryRunUserPreferenceRepository.class)
                  .hasSingleBean(
                      com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository
                          .class);
              var store =
                  context.getBean(
                      com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository
                          .class);
              if (backend.equals("qdrant")) {
                assertThat(store)
                    .isInstanceOf(
                        com.gen3.recommenderagent.storage.user.qdrant.QdrantUserPreferenceRepository
                            .class);
              } else {
                assertThat(store)
                    .isInstanceOf(
                        com.gen3.recommenderagent.storage.user.solr.SolrUserPreferenceRepository
                            .class);
              }
            });
  }
}
