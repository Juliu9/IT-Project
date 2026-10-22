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
                    .doesNotHaveBean(com.gen3.recommenderagent.api.RequestGateway.class)
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
}
