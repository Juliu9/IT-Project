package com.gen3.shortdescriptionjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.model.StoredAudiobook;
import com.gen3.shortdescriptionjob.provider.OpenAiShortDescriptionBatchProvider;
import com.gen3.shortdescriptionjob.provider.ShortDescriptionBatchProvider;
import com.gen3.shortdescriptionjob.store.AudiobookEnrichmentStore;
import com.gen3.shortdescriptionjob.store.AudiobookEnrichmentStore.DatasetIdentity;
import com.gen3.shortdescriptionjob.store.AudiobookEnrichmentStore.ShortDescriptionUpdate;
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
        store,
        mapper,
        new OpenAiShortDescriptionBatchProvider(mapper, client, "test-key", url, "gpt-4o-mini"),
        mode,
        "",
        directory,
        1);
  }

  private StoredAudiobook book() {
    return new StoredAudiobook(
        new AudiobookRecord("book-1", "catalogue", "Title", List.of(), "A long description."),
        "opaque-database-row");
  }

  @Test
  void refusesDuplicateBookIdsBeforeUploadOrManifestCreation() throws Exception {
    var duplicate = new StoredAudiobook(book().record(), "another-point");
    when(store.findRecordsWithoutShortDescription(2)).thenReturn(List.of(book(), duplicate));
    var provider = mock(ShortDescriptionBatchProvider.class);
    var job =
        new BatchShortDescriptionEnricher(store, mapper, provider, "submit", "", directory, 2);
    assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate bookId");
    verifyNoInteractions(provider);
    assertThat(directory.resolve("short-description-batch.json")).doesNotExist();
  }

  @Test
  void recoveryArchivesTheManifestAndAllowsResubmission() throws Exception {
    when(store.datasetIdentity()).thenReturn(new DatasetIdentity("other", "catalogue-a"));
    var provider = mock(ShortDescriptionBatchProvider.class);
    when(provider.id()).thenReturn("openai");
    var recover =
        new BatchShortDescriptionEnricher(store, mapper, provider, "recover", "", directory, 1);
    Path pending = directory.resolve("short-description-batch.json");
    recover.writeManifest(pending, "batch-1", List.of(book()));
    String original = Files.readString(pending);
    recover.run(new DefaultApplicationArguments());
    verify(provider).verifyTerminalFailure("batch-1");
    assertThat(pending).doesNotExist();
    try (var files = Files.list(directory)) {
      var archives = files.toList();
      assertThat(archives).hasSize(1);
      assertThat(archives.getFirst().getFileName().toString())
          .startsWith("short-description-batch-recovered-");
      assertThat(Files.readString(archives.getFirst())).isEqualTo(original);
    }
    verify(store, never()).updateShortDescriptions(any());
    when(store.findRecordsWithoutShortDescription(1)).thenReturn(List.of(book()));
    when(provider.upload(List.of(book().record()), directory)).thenReturn("input-2");
    when(provider.submit("input-2")).thenReturn("batch-2");
    new BatchShortDescriptionEnricher(store, mapper, provider, "submit", "", directory, 1)
        .run(new DefaultApplicationArguments());
    assertThat(mapper.readTree(pending.toFile()).path("batchId").asText()).isEqualTo("batch-2");
  }

  @Test
  void rejectedRecoveryPreservesThePendingManifest() throws Exception {
    when(store.datasetIdentity()).thenReturn(new DatasetIdentity("other", "catalogue-a"));
    var provider = mock(ShortDescriptionBatchProvider.class);
    when(provider.id()).thenReturn("openai");
    org.mockito.Mockito.doThrow(new java.io.IOException("status unavailable"))
        .when(provider)
        .verifyTerminalFailure("batch-1");
    var recover =
        new BatchShortDescriptionEnricher(store, mapper, provider, "recover", "", directory, 1);
    Path pending = directory.resolve("short-description-batch.json");
    recover.writeManifest(pending, "batch-1", List.of(book()));
    String original = Files.readString(pending);
    assertThatThrownBy(() -> recover.run(new DefaultApplicationArguments()))
        .isInstanceOf(java.io.IOException.class);
    assertThat(Files.readString(pending)).isEqualTo(original);
    try (var files = Files.list(directory)) {
      assertThat(files.toList()).containsExactly(pending);
    }
    verify(store, never()).updateShortDescriptions(any());
  }

  @Test
  void recoveryDoesNotAllowChangingThePendingBatchId() throws Exception {
    when(store.datasetIdentity()).thenReturn(new DatasetIdentity("other", "catalogue-a"));
    var provider = mock(ShortDescriptionBatchProvider.class);
    when(provider.id()).thenReturn("openai");
    var recover =
        new BatchShortDescriptionEnricher(
            store, mapper, provider, "recover", "another", directory, 1);
    Path pending = directory.resolve("short-description-batch.json");
    recover.writeManifest(pending, "batch-1", List.of(book()));
    assertThatThrownBy(() -> recover.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
    verify(provider, never()).verifyTerminalFailure(any());
    assertThat(pending).exists();
  }

  @Test
  void replacementProviderUsesTheSameLifecycleAndSharedDescriptionRules() throws Exception {
    when(store.datasetIdentity()).thenReturn(new DatasetIdentity("other", "catalogue-a"));
    when(store.findRecordsWithoutShortDescription(1)).thenReturn(List.of(book()));
    ShortDescriptionBatchProvider provider = mock(ShortDescriptionBatchProvider.class);
    when(provider.id()).thenReturn("replacement");
    when(provider.upload(List.of(book().record()), directory)).thenReturn("input-other");
    when(provider.submit("input-other")).thenReturn("batch-other");
    new BatchShortDescriptionEnricher(store, mapper, provider, "submit", "", directory, 1)
        .run(new DefaultApplicationArguments());
    when(provider.completedDescriptions("batch-other"))
        .thenReturn(
            java.util.Map.of(
                "book-1",
                    "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one",
                "unknown-book", "Unrequested book."));
    new BatchShortDescriptionEnricher(store, mapper, provider, "import", "", directory, 1)
        .run(new DefaultApplicationArguments());
    verify(store)
        .updateShortDescriptions(
            List.of(
                new ShortDescriptionUpdate(
                    "opaque-database-row",
                    "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty")));
    assertThat(directory.resolve("short-description-batch.json")).doesNotExist();
  }

  @Test
  void refusesAChangedProviderBeforeCallingItOrWritingToTheDatabase() throws Exception {
    when(store.datasetIdentity()).thenReturn(new DatasetIdentity("other", "catalogue-a"));
    job("disabled", mock(HttpClient.class), "https://unused.test")
        .writeManifest(
            directory.resolve("short-description-batch.json"), "batch-1", List.of(book()));
    ShortDescriptionBatchProvider replacement = mock(ShortDescriptionBatchProvider.class);
    when(replacement.id()).thenReturn("replacement");
    var job =
        new BatchShortDescriptionEnricher(store, mapper, replacement, "import", "", directory, 1);
    assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("provider does not match");
    verify(replacement, never()).completedDescriptions(any());
    verify(store, never()).updateShortDescriptions(any());
    assertThat(directory.resolve("short-description-batch.json")).exists();
  }

  @Test
  void readsExistingOpenAiManifestsWithoutAProviderField() throws Exception {
    when(store.datasetIdentity()).thenReturn(new DatasetIdentity("other", "catalogue-a"));
    Path manifest = directory.resolve("short-description-batch.json");
    job("disabled", mock(HttpClient.class), "https://unused.test")
        .writeManifest(manifest, "batch-1", List.of(book()));
    var stored = (tools.jackson.databind.node.ObjectNode) mapper.readTree(manifest.toFile());
    stored.remove("provider");
    mapper.writeValue(manifest.toFile(), stored);
    assertThat(
            job("disabled", mock(HttpClient.class), "https://unused.test")
                .pendingStorageReferences())
        .containsEntry("book-1", "opaque-database-row");
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
  void failedUploadCanBeResubmittedWithoutManualManifestRecovery() throws Exception {
    when(store.datasetIdentity()).thenReturn(new DatasetIdentity("other", "catalogue-a"));
    when(store.findRecordsWithoutShortDescription(1)).thenReturn(List.of(book()));
    var uploads = new java.util.concurrent.atomic.AtomicInteger();
    var submissions = new java.util.concurrent.atomic.AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if (exchange.getRequestURI().getPath().equals("/files")
              && uploads.incrementAndGet() == 1) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
          }
          String response;
          if (exchange.getRequestURI().getPath().equals("/files")) {
            response = "{\"id\":\"input-1\"}";
          } else {
            submissions.incrementAndGet();
            response = "{\"id\":\"batch-1\"}";
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
      var submit = job("submit", HttpClient.newHttpClient(), url);
      assertThatThrownBy(() -> submit.run(new DefaultApplicationArguments()))
          .isInstanceOf(java.io.IOException.class);
      assertThat(directory.resolve("short-description-batch.json")).doesNotExist();
      assertThat(submissions.get()).isZero();
      submit.run(new DefaultApplicationArguments());
      assertThat(submissions.get()).isEqualTo(1);
      assertThat(
              mapper
                  .readTree(directory.resolve("short-description-batch.json").toFile())
                  .path("batchId")
                  .asText())
          .isEqualTo("batch-1");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void uncertainSubmissionKeepsItsManifestAndCannotBeBlindlyRetried() throws Exception {
    when(store.datasetIdentity()).thenReturn(new DatasetIdentity("other", "catalogue-a"));
    when(store.findRecordsWithoutShortDescription(1)).thenReturn(List.of(book()));
    var submissions = new java.util.concurrent.atomic.AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if (exchange.getRequestURI().getPath().equals("/files")) {
            byte[] body = "{\"id\":\"input-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) {
              stream.write(body);
            }
          } else {
            submissions.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
          }
        });
    server.start();
    try {
      var submit =
          job(
              "submit",
              HttpClient.newHttpClient(),
              "http://127.0.0.1:" + server.getAddress().getPort());
      assertThatThrownBy(() -> submit.run(new DefaultApplicationArguments()))
          .isInstanceOf(java.io.IOException.class);
      assertThat(directory.resolve("short-description-batch.json")).exists();
      assertThatThrownBy(() -> submit.run(new DefaultApplicationArguments()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("still pending");
      assertThat(submissions.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void failedManifestReplacementPreservesThePreviousRecoveryData() throws Exception {
    when(store.datasetIdentity()).thenReturn(new DatasetIdentity("other", "catalogue-a"));
    Path manifest = directory.resolve("short-description-batch.json");
    job("disabled", mock(HttpClient.class), "https://unused.test")
        .writeManifest(manifest, "", List.of(book()));
    String previous = Files.readString(manifest);
    ObjectMapper failingMapper = spy(new ObjectMapper());
    doAnswer(
            invocation -> {
              java.io.File target = invocation.getArgument(0);
              Files.writeString(target.toPath(), "{partial");
              throw new java.io.IOException("simulated disk failure");
            })
        .when(failingMapper)
        .writeValue(any(java.io.File.class), any());
    var job =
        new BatchShortDescriptionEnricher(
            store,
            failingMapper,
            new OpenAiShortDescriptionBatchProvider(
                mapper, mock(HttpClient.class), "test-key", "https://unused.test", "gpt-4o-mini"),
            "disabled",
            "",
            directory,
            1);
    assertThatThrownBy(() -> job.writeManifest(manifest, "batch-1", List.of(book())))
        .isInstanceOf(java.io.IOException.class);
    assertThat(Files.readString(manifest)).isEqualTo(previous);
    try (var files = Files.list(directory)) {
      assertThat(files.toList()).containsExactly(manifest);
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
