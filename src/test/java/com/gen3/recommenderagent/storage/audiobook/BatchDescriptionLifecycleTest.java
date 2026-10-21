package com.gen3.recommenderagent.storage.audiobook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore.DatasetIdentity;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore.ShortDescriptionUpdate;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore.StoredAudiobook;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.ObjectMapper;

class BatchDescriptionLifecycleTest {
  @TempDir Path directory;
  private final AudiobookEnrichmentStore store = mock(AudiobookEnrichmentStore.class);
  private final ObjectMapper mapper = new ObjectMapper();

  private BatchShortDescriptionEnricher job(String mode, HttpClient client, String url) {
    return new BatchShortDescriptionEnricher(
        store, mapper, client, "test-key", url, mode, "", directory, 1);
  }

  private StoredAudiobook book() {
    return new StoredAudiobook(
        new AudiobookRecord("book-1", "catalogue", "Title", List.of(), "A long description."),
        "opaque-database-row");
  }

  @Test
  void submitsAndImportsThroughAReplacementDatabaseAdapter() throws Exception {
    when(store.datasetIdentity()).thenReturn(new DatasetIdentity("other", "catalogue-a"));
    when(store.findRecordsWithoutShortDescription(1)).thenReturn(List.of(book()));
    var submitted = new java.util.concurrent.atomic.AtomicReference<String>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String response;
          if (path.equals("/files")) {
            String input =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            submitted.set(input);
            response = "{\"id\":\"input-1\"}";
          } else if (path.equals("/batches")) {
            response = "{\"id\":\"batch-1\"}";
          } else if (path.equals("/batches/batch-1")) {
            response = "{\"status\":\"completed\",\"output_file_id\":\"output-1\"}";
          } else {
            var content = mapper.createObjectNode().put("book-1", "A short description.");
            var output = mapper.createObjectNode();
            var result = output.putObject("response");
            result.put("status_code", 200);
            result
                .putObject("body")
                .putArray("choices")
                .addObject()
                .putObject("message")
                .put("content", content.toString());
            response = output.toString();
          }
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var stream = exchange.getResponseBody()) {
            stream.write(body);
          }
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      job("submit", HttpClient.newHttpClient(), url).run(new DefaultApplicationArguments());
      assertThat(submitted.get()).contains("A long description.");
      var manifest = mapper.readTree(directory.resolve("short-description-batch.json").toFile());
      assertThat(manifest.path("origin").path("backend").asText()).isEqualTo("other");
      assertThat(manifest.path("origin").path("dataset").asText()).isEqualTo("catalogue-a");
      job("import", HttpClient.newHttpClient(), url).run(new DefaultApplicationArguments());
      verify(store)
          .updateShortDescriptions(
              List.of(new ShortDescriptionUpdate("opaque-database-row", "A short description.")));
      assertThat(directory.resolve("short-description-batch.json")).doesNotExist();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void refusesChangedBackendBeforeProviderCallsOrDatabaseWrites() throws Exception {
    refusesChangedOrigin(new DatasetIdentity("other", "catalogue-a"));
  }

  @Test
  void refusesChangedDatasetBeforeProviderCallsOrDatabaseWrites() throws Exception {
    refusesChangedOrigin(new DatasetIdentity("original", "catalogue-b"));
  }

  private void refusesChangedOrigin(DatasetIdentity selected) throws Exception {
    when(store.datasetIdentity()).thenReturn(new DatasetIdentity("original", "catalogue-a"));
    HttpClient client = mock(HttpClient.class);
    job("disabled", client, "https://unused.test")
        .writeManifest(
            directory.resolve("short-description-batch.json"), "batch-1", List.of(book()));
    when(store.datasetIdentity()).thenReturn(selected);
    assertThatThrownBy(
            () ->
                job("import", client, "https://unused.test").run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match");
    verifyNoInteractions(client);
    verify(store, never()).updateShortDescriptions(any());
    assertThat(directory.resolve("short-description-batch.json")).exists();
  }

  @Test
  void refusesLegacyManifestWithoutDeletingIt() throws Exception {
    Files.writeString(
        directory.resolve("short-description-batch.json"),
        "{\"batchId\":\"batch-1\",\"pointIds\":{\"book-1\":\"opaque-row\"}}");
    HttpClient client = mock(HttpClient.class);
    assertThatThrownBy(
            () ->
                job("import", client, "https://unused.test").run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Legacy batch manifest");
    verifyNoInteractions(client, store);
    assertThat(directory.resolve("short-description-batch.json")).exists();
  }
}
