package com.gen3.recommenderagent.storage.audiobook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore;
import com.gen3.recommenderagent.storage.audiobook.qdrant.Bm25SparseTextEncoder;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantPointMapper;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BatchShortDescriptionEnricherTest {

  @Test
  void shouldReadCompletedBatchDescriptionsByBookId() throws Exception {
    BatchShortDescriptionEnricher enricher = enricher(Path.of("target"));

    String output =
        """
        {"response":{"status_code":200,"body":{"choices":[{"message":{"content":"{\\\"book-1\\\":\\\"A concise description.\\\"}"}}]}}}
        {"response":{"status_code":200,"body":{"choices":[{"message":{"content":"{\\\"book-2\\\":\\\"An incomplete description"}}]}}}
        {"response":{"status_code":400,"body":{}}}
        """;

    assertThat(enricher.parseDescriptions(output))
        .containsExactlyEntriesOf(java.util.Map.of("book-1", "A concise description."));
  }

  @Test
  void trimsDescriptionsToTwentyWordsBeforeStoringThem() throws Exception {
    BatchShortDescriptionEnricher enricher = enricher(Path.of("target"));
    String output =
        "{\"response\":{\"status_code\":200,\"body\":{\"choices\":[{\"message\":{\"content\":\"{\\\"book-1\\\":\\\"one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one\\\"}\"}}]}}}";

    assertThat(enricher.parseDescriptions(output).get("book-1"))
        .isEqualTo(
            "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty");
  }

  @Test
  void shouldPersistTheStorageReferencesSubmittedToOpenAi() throws Exception {
    Path directory = Files.createTempDirectory("short-description-test");
    BatchShortDescriptionEnricher enricher = enricher(directory);
    QdrantPointMapper mapper = new QdrantPointMapper(new Bm25SparseTextEncoder());
    var pointId = mapper.pointId("book-1");
    String storageReference = Base64.getEncoder().encodeToString(pointId.toByteArray());
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
    BatchShortDescriptionEnricher enricher = enricher(Path.of("target"));
    var request =
        enricher.batchRequest(
            List.of(
                new AudiobookEnrichmentStore.StoredAudiobook(
                    new AudiobookRecord("book-1", "source", "Title", List.of(), "Summary"),
                    Base64.getEncoder()
                        .encodeToString(
                            new QdrantPointMapper(new Bm25SparseTextEncoder())
                                .pointId("book-1")
                                .toByteArray()))),
            0);

    var body = request.path("body");
    assertThat(body.path("model").asText()).isEqualTo("gpt-4o-mini");
    assertThat(body.path("max_tokens").asInt()).isEqualTo(600);
    assertThat(body.path("messages").get(0).path("content").asText()).contains("at most 20 words");
  }

  private BatchShortDescriptionEnricher enricher(Path outputDirectory) {
    return new BatchShortDescriptionEnricher(
        mock(AudiobookEnrichmentStore.class),
        new ObjectMapper(),
        HttpClient.newHttpClient(),
        "test-key",
        "https://example.test/v1",
        "disabled",
        "",
        outputDirectory,
        1);
  }
}
