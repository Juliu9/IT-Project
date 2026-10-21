package com.gen3.recommenderagent.storage.audiobook;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates and imports OpenAI Batch jobs that add one short, spoiler-free description per book. This
 * job is explicitly opt-in and never runs on normal application startup.
 */
@Component
@ConditionalOnExpression("'${audiobook.short-description.batch.mode:disabled}' != 'disabled'")
public class BatchShortDescriptionEnricher implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(BatchShortDescriptionEnricher.class);
  private static final int BOOKS_PER_REQUEST = 10;
  private static final int MAX_DESCRIPTION_WORDS = 20;
  private final AudiobookEnrichmentStore enrichmentStore;
  private final ObjectMapper mapper;
  private final HttpClient client;
  private final String apiKey;
  private final String apiUrl;
  private final String mode;
  private final String batchId;
  private final Path outputDirectory;
  private final int maximumRecords;

  @Autowired
  public BatchShortDescriptionEnricher(
      AudiobookEnrichmentStore enrichmentStore,
      ObjectMapper mapper,
      @Value("${spring.ai.openai.api-key}") String apiKey,
      @Value("${audiobook.short-description.batch.mode:disabled}") String mode,
      @Value("${audiobook.short-description.batch.id:}") String batchId,
      @Value("${audiobook.short-description.batch.output-directory:./data/short-descriptions}")
          Path outputDirectory,
      @Value("${audiobook.short-description.batch.maximum-records:100000}") int maximumRecords) {
    this(
        enrichmentStore,
        mapper,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
        apiKey,
        "https://api.openai.com/v1",
        mode,
        batchId,
        outputDirectory,
        maximumRecords);
  }

  BatchShortDescriptionEnricher(
      AudiobookEnrichmentStore enrichmentStore,
      ObjectMapper mapper,
      HttpClient client,
      String apiKey,
      String apiUrl,
      String mode,
      String batchId,
      Path outputDirectory,
      int maximumRecords) {
    this.enrichmentStore = enrichmentStore;
    this.mapper = mapper;
    this.client = client;
    this.apiKey = apiKey;
    this.apiUrl = apiUrl;
    this.mode = mode;
    this.batchId = batchId;
    this.outputDirectory = outputDirectory;
    this.maximumRecords = Math.max(1, maximumRecords);
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    if ("disabled".equalsIgnoreCase(mode)) {
      return;
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "OPENAI_API_KEY is required for short-description enrichment");
    }
    if ("submit".equalsIgnoreCase(mode)) {
      submit();
      return;
    }
    if ("import".equalsIgnoreCase(mode)) {
      importCompletedBatch();
      return;
    }
    throw new IllegalArgumentException("Unknown audiobook.short-description.batch.mode: " + mode);
  }

  private void submit() throws IOException, InterruptedException {
    Path manifest = manifestPath();
    if (Files.exists(manifest)) {
      throw new IllegalStateException(
          "A short-description batch is still pending at "
              + manifest
              + ". Import it before submitting another batch.");
    }
    List<AudiobookEnrichmentStore.StoredAudiobook> books =
        enrichmentStore.findRecordsWithoutShortDescription(maximumRecords);
    Files.createDirectories(outputDirectory);
    Path input = Files.createTempFile(outputDirectory, "short-descriptions-", ".jsonl");
    List<AudiobookEnrichmentStore.StoredAudiobook> validBooks = validBooks(books);
    int records = writeBatchInput(input, validBooks);
    if (records == 0) {
      Files.deleteIfExists(input);
      LOGGER.info("No audiobook descriptions were available for short-description enrichment");
      return;
    }

    // Uploads cannot create a generation batch, so upload failure is safe to retry.
    JsonNode uploaded = upload(input);
    ObjectNode request = mapper.createObjectNode();
    request.put("input_file_id", uploaded.required("id").asText());
    request.put("endpoint", "/v1/chat/completions");
    request.put("completion_window", "24h");
    // Once submission starts, an uncertain outcome must retain the record references.
    writeManifest(manifest, "", validBooks);
    JsonNode batch;
    try {
      batch = postJson("/batches", request);
    } catch (IOException exception) {
      throw new IOException(
          "Batch submission may have been accepted. The pending manifest is retained. Reconcile the provider's batch ID and set AUDIOBOOK_SHORT_DESCRIPTION_BATCH_ID to import it before retrying.",
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      InterruptedException interrupted =
          new InterruptedException(
              "Batch submission was interrupted; its outcome is unknown. The pending manifest is retained. Reconcile the provider's batch ID before retrying.");
      interrupted.initCause(exception);
      throw interrupted;
    }
    String submittedBatchId = batch.required("id").asText();
    writeManifest(manifest, submittedBatchId, validBooks);
    LOGGER.info(
        "Submitted short-description batch {} for {} records. Set AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=import after it completes.",
        submittedBatchId,
        records);
  }

  private int writeBatchInput(Path input, List<AudiobookEnrichmentStore.StoredAudiobook> books)
      throws IOException {
    int loaded = 0;
    try (var writer = Files.newBufferedWriter(input, StandardCharsets.UTF_8)) {
      for (List<AudiobookEnrichmentStore.StoredAudiobook> group : groups(books)) {
        writer.write(mapper.writeValueAsString(batchRequest(group, loaded)));
        writer.newLine();
        loaded += group.size();
      }
    }
    return loaded;
  }

  private List<AudiobookEnrichmentStore.StoredAudiobook> validBooks(
      List<AudiobookEnrichmentStore.StoredAudiobook> records) {
    return records.stream()
        .filter(
            record ->
                record != null && record.record() != null && hasText(record.storageReference()))
        .filter(record -> hasText(record.record().id()) && hasText(record.record().description()))
        .toList();
  }

  private List<List<AudiobookEnrichmentStore.StoredAudiobook>> groups(
      List<AudiobookEnrichmentStore.StoredAudiobook> records) {
    List<List<AudiobookEnrichmentStore.StoredAudiobook>> groups = new ArrayList<>();
    for (int start = 0; start < records.size(); start += BOOKS_PER_REQUEST) {
      groups.add(records.subList(start, Math.min(start + BOOKS_PER_REQUEST, records.size())));
    }
    return groups;
  }

  ObjectNode batchRequest(List<AudiobookEnrichmentStore.StoredAudiobook> books, int requestNumber) {
    ObjectNode request = mapper.createObjectNode();
    request.put("custom_id", "short-description-" + requestNumber);
    request.put("method", "POST");
    request.put("url", "/v1/chat/completions");
    ObjectNode body = request.putObject("body");
    body.put("model", "gpt-4o-mini");
    body.put("temperature", 0);
    body.put("max_tokens", 600);
    body.putObject("response_format").put("type", "json_object");
    ArrayNode messages = body.putArray("messages");
    messages
        .addObject()
        .put("role", "system")
        .put(
            "content",
            "Write one meaningful, spoiler-free sentence of at most 20 words for each audiobook. Return only a JSON object mapping each supplied ID to its sentence.");
    ArrayNode booksJson = mapper.createArrayNode();
    for (AudiobookEnrichmentStore.StoredAudiobook storedBook : books) {
      AudiobookRecord book = storedBook.record();
      booksJson
          .addObject()
          .put("id", book.id())
          .put("title", book.title())
          .put("description", book.description());
    }
    messages.addObject().put("role", "user").put("content", booksJson.toString());
    return request;
  }

  private JsonNode upload(Path input) throws IOException, InterruptedException {
    String boundary = "----recommender-" + UUID.randomUUID();
    HttpRequest request =
        authorizedRequest("/files")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(multipart(input, boundary))
            .build();
    return sendJson(request);
  }

  private HttpRequest.BodyPublisher multipart(Path input, String boundary) throws IOException {
    return HttpRequest.BodyPublishers.concat(
        HttpRequest.BodyPublishers.ofString(
            "--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nbatch\r\n"
                + "--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + input.getFileName()
                + "\"\r\nContent-Type: application/jsonl\r\n\r\n",
            StandardCharsets.UTF_8),
        HttpRequest.BodyPublishers.ofFile(input),
        HttpRequest.BodyPublishers.ofString(
            "\r\n--" + boundary + "--\r\n", StandardCharsets.UTF_8));
  }

  private void importCompletedBatch() throws IOException, InterruptedException {
    BatchManifest manifest = readManifest();
    String importedBatchId = hasText(batchId) ? batchId : manifest.batchId();
    if (!hasText(importedBatchId)) {
      throw new IllegalArgumentException(
          "audiobook.short-description.batch.id is required because the pending manifest has no batch ID");
    }
    if (hasText(manifest.batchId()) && !manifest.batchId().equals(importedBatchId)) {
      throw new IllegalArgumentException(
          "Configured batch ID does not match the pending short-description batch");
    }
    JsonNode batch = getJson("/batches/" + importedBatchId);
    if (!"completed".equals(batch.path("status").asText())) {
      throw new IllegalStateException(
          "Batch " + importedBatchId + " is " + batch.path("status").asText());
    }
    String outputFileId = batch.path("output_file_id").asText();
    if (!hasText(outputFileId)) {
      throw new IllegalStateException("Completed batch did not provide an output file");
    }
    Map<String, String> descriptions =
        parseDescriptions(getText("/files/" + outputFileId + "/content"));
    List<AudiobookEnrichmentStore.ShortDescriptionUpdate> updates =
        descriptions.entrySet().stream()
            .filter(entry -> manifest.storageReferences().containsKey(entry.getKey()))
            .map(
                entry ->
                    new AudiobookEnrichmentStore.ShortDescriptionUpdate(
                        manifest.storageReferences().get(entry.getKey()), entry.getValue()))
            .toList();
    enrichmentStore.updateShortDescriptions(updates);
    Files.delete(manifestPath());
    LOGGER.info("Imported {} generated short audiobook descriptions", updates.size());
  }

  private Path manifestPath() {
    return outputDirectory.resolve("short-description-batch.json");
  }

  void writeManifest(
      Path manifest, String submittedBatchId, List<AudiobookEnrichmentStore.StoredAudiobook> books)
      throws IOException {
    Map<String, String> storageReferences = new LinkedHashMap<>();
    for (AudiobookEnrichmentStore.StoredAudiobook book : books) {
      storageReferences.put(book.record().id(), book.storageReference());
    }
    Path temporary =
        Files.createTempFile(manifest.toAbsolutePath().getParent(), "batch-manifest-", ".tmp");
    try {
      mapper.writeValue(
          temporary.toFile(),
          new StoredBatchManifest(
              submittedBatchId, enrichmentStore.datasetIdentity(), storageReferences));
      Files.move(
          temporary, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private BatchManifest readManifest() throws IOException {
    Path manifest = manifestPath();
    if (!Files.exists(manifest)) {
      throw new IllegalStateException(
          "No pending short-description batch manifest was found at "
              + manifest
              + ". Import cannot safely identify the stored audiobooks.");
    }
    StoredBatchManifest stored = mapper.readValue(manifest.toFile(), StoredBatchManifest.class);
    if (stored.origin() == null) {
      throw new IllegalStateException(
          "Legacy batch manifest has no database identity. Import is refused; the manifest is unchanged. Restore its originating configuration and recover the batch explicitly before submitting another.");
    }
    if (!stored.origin().equals(enrichmentStore.datasetIdentity())) {
      throw new IllegalStateException(
          "Batch database/dataset does not match the selected database. Restore its originating configuration before importing; the manifest is unchanged.");
    }
    return new BatchManifest(stored.batchId(), stored.pointIds());
  }

  Map<String, String> pendingStorageReferences() throws IOException {
    return readManifest().storageReferences();
  }

  private record StoredBatchManifest(
      String batchId,
      AudiobookEnrichmentStore.DatasetIdentity origin,
      Map<String, String> pointIds) {}

  private record BatchManifest(String batchId, Map<String, String> storageReferences) {}

  Map<String, String> parseDescriptions(String output) throws IOException {
    Map<String, String> descriptions = new LinkedHashMap<>();
    for (String line : output.lines().toList()) {
      JsonNode result = mapper.readTree(line);
      if (result.path("response").path("status_code").asInt() != 200) {
        continue;
      }
      String content =
          result
              .path("response")
              .path("body")
              .path("choices")
              .path(0)
              .path("message")
              .path("content")
              .asText();
      if (!hasText(content)) {
        continue;
      }
      JsonNode response;
      try {
        response = mapper.readTree(content);
      } catch (JacksonException exception) {
        LOGGER.warn("Skipping malformed short-description response");
        continue;
      }
      response
          .properties()
          .forEach(
              entry -> {
                String description = entry.getValue().asText();
                if (hasText(entry.getKey()) && hasText(description)) {
                  descriptions.put(entry.getKey(), trimToWordLimit(description));
                }
              });
    }
    return descriptions;
  }

  private JsonNode postJson(String path, JsonNode body) throws IOException, InterruptedException {
    HttpRequest request =
        authorizedRequest(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
    return sendJson(request);
  }

  private JsonNode getJson(String path) throws IOException, InterruptedException {
    return mapper.readTree(getText(path));
  }

  private String getText(String path) throws IOException, InterruptedException {
    return sendText(authorizedRequest(path).GET().build());
  }

  private String sendText(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException(
          "OpenAI API returned " + response.statusCode() + ": " + response.body());
    }
    return response.body();
  }

  private JsonNode sendJson(HttpRequest request) throws IOException, InterruptedException {
    return mapper.readTree(sendText(request));
  }

  private HttpRequest.Builder authorizedRequest(String path) {
    return HttpRequest.newBuilder(URI.create(apiUrl + path))
        .header("Authorization", "Bearer " + apiKey);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String trimToWordLimit(String description) {
    String[] words = description.trim().split("\\s+");
    return String.join(
        " ", java.util.Arrays.copyOf(words, Math.min(words.length, MAX_DESCRIPTION_WORDS)));
  }
}
