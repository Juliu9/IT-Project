package com.gen3.recommenderagent.storage.audiobook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.openai.OpenAiShortDescriptionBatchProvider;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore;
import com.gen3.recommenderagent.storage.audiobook.port.ShortDescriptionBatchProvider;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class BatchShortDescriptionEnricherTest {

  @Test
  void shouldReadCompletedBatchDescriptionsByBookId() throws Exception {

    String output =
        """
        {"response":{"status_code":200,"body":{"choices":[{"message":{"content":"{\\\"book-1\\\":\\\"A concise description.\\\"}"}}]}}}
        {"response":{"status_code":200,"body":{"choices":[{"message":{"content":"{\\\"book-2\\\":\\\"An incomplete description"}}]}}}
        {"response":{"status_code":400,"body":{}}}
        """;

    assertThat(provider().parseDescriptions(output))
        .containsExactlyEntriesOf(java.util.Map.of("book-1", "A concise description."));
  }

  @Test
  void shouldPersistTheStorageReferencesSubmittedToOpenAi() throws Exception {
    Path directory = Files.createTempDirectory("short-description-test");
    BatchShortDescriptionEnricher enricher = enricher(directory);
    String storageReference = "opaque-row-1";
    var book =
        new AudiobookEnrichmentStore.StoredAudiobook(
            new AudiobookRecord("book-1", "source", "Title", List.of(), "Summary"),
            storageReference);

    enricher.writeManifest(
        directory.resolve("short-description-batch.json"), "batch-1", List.of(book));

    assertThat(enricher.pendingStorageReferences())
        .containsExactlyEntriesOf(java.util.Map.of("book-1", storageReference));
  }

  @Test
  void shouldRequestTwentyWordShortDescriptions() {
    var request =
        provider()
            .batchRequest(
                List.of(new AudiobookRecord("book-1", "source", "Title", List.of(), "Summary")), 0);

    var body = request.path("body");
    assertThat(body.path("model").asText()).isEqualTo("gpt-4o-mini");
    assertThat(body.path("max_tokens").asInt()).isEqualTo(600);
    assertThat(body.path("messages").get(0).path("content").asText()).contains("at most 20 words");
  }

  @Test
  void selectingAReplacementProviderDoesNotRequireOpenAiCredentials() {
    ShortDescriptionBatchProvider replacement = mock(ShortDescriptionBatchProvider.class);
    new ApplicationContextRunner()
        .withUserConfiguration(
            BatchShortDescriptionEnricher.class, OpenAiShortDescriptionBatchProvider.class)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(AudiobookEnrichmentStore.class, () -> mock(AudiobookEnrichmentStore.class))
        .withBean(ShortDescriptionBatchProvider.class, () -> replacement)
        .withPropertyValues(
            "audiobook.short-description.batch.mode=submit",
            "audiobook.short-description.batch.provider=other")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(BatchShortDescriptionEnricher.class)
                  .doesNotHaveBean(OpenAiShortDescriptionBatchProvider.class);
              assertThat(context.getBean(ShortDescriptionBatchProvider.class))
                  .isSameAs(replacement);
            });
  }

  @Test
  void openAiBatchModelCanBeConfiguredIndependently() {
    new ApplicationContextRunner()
        .withUserConfiguration(OpenAiShortDescriptionBatchProvider.class)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues(
            "audiobook.short-description.batch.mode=submit",
            "spring.ai.openai.api-key=unused",
            "audiobook.short-description.batch.openai.model=batch-model")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var request =
                  context
                      .getBean(OpenAiShortDescriptionBatchProvider.class)
                      .batchRequest(
                          List.of(
                              new AudiobookRecord(
                                  "book-1", "source", "Title", List.of(), "Summary")),
                          0);
              assertThat(request.path("body").path("model").asText()).isEqualTo("batch-model");
            });
  }

  private OpenAiShortDescriptionBatchProvider provider() {
    return new OpenAiShortDescriptionBatchProvider(
        new ObjectMapper(),
        HttpClient.newHttpClient(),
        "test-key",
        "https://example.test/v1",
        "gpt-4o-mini");
  }

  private BatchShortDescriptionEnricher enricher(Path outputDirectory) {
    AudiobookEnrichmentStore store = mock(AudiobookEnrichmentStore.class);
    when(store.datasetIdentity())
        .thenReturn(new AudiobookEnrichmentStore.DatasetIdentity("test-db", "books"));
    return new BatchShortDescriptionEnricher(
        store, new ObjectMapper(), provider(), "disabled", "", outputDirectory, 1);
  }
}
