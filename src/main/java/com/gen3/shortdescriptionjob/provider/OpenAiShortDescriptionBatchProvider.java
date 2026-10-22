package com.gen3.shortdescriptionjob.provider;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnExpression("'${audiobook.short-description.batch.mode:disabled}' != 'disabled'")
@ConditionalOnProperty(
    name = "audiobook.short-description.batch.provider",
    havingValue = "openai",
    matchIfMissing = true)
public class OpenAiShortDescriptionBatchProvider implements ShortDescriptionBatchProvider {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(OpenAiShortDescriptionBatchProvider.class);
  private static final int BOOKS_PER_REQUEST = 10;
  private final ObjectMapper mapper;
  private final HttpClient client;
  private final String apiKey;
  private final String apiUrl;
  private final String model;
  private final Duration requestTimeout;
  private final Duration pollInterval;

  @Autowired
  public OpenAiShortDescriptionBatchProvider(
      ObjectMapper mapper,
      @Value("${spring.ai.openai.api-key}") String apiKey,
      @Value("${audiobook.short-description.batch.openai.base-url:https://api.openai.com/v1}")
          String apiUrl,
      @Value("${audiobook.short-description.batch.openai.model:gpt-4o-mini}") String model) {
    this(
        mapper,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
        apiKey,
        apiUrl,
        model);
  }

  public OpenAiShortDescriptionBatchProvider(
      ObjectMapper mapper, HttpClient client, String apiKey, String apiUrl, String model) {
    this(mapper, client, apiKey, apiUrl, model, Duration.ofMinutes(15), Duration.ofSeconds(30));
  }

  OpenAiShortDescriptionBatchProvider(
      ObjectMapper mapper,
      HttpClient client,
      String apiKey,
      String apiUrl,
      String model,
      Duration requestTimeout,
      Duration pollInterval) {
    this.requestTimeout = requestTimeout;
    this.pollInterval = pollInterval;
    this.mapper = mapper;
    this.client = client;
    this.apiKey = apiKey;
    this.apiUrl = apiUrl;
    this.model = model;
  }

  @Override
  public String id() {
    return "openai";
  }

  @Override
  public String upload(List<AudiobookRecord> books, Path directory)
      throws IOException, InterruptedException {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "OPENAI_API_KEY is required for short-description enrichment");
    }
    Path input = Files.createTempFile(directory, "short-descriptions-", ".jsonl");
    writeBatchInput(input, books);
    return upload(input).required("id").asText();
  }

  @Override
  public String submit(String inputId) throws IOException, InterruptedException {
    ObjectNode request = mapper.createObjectNode();
    request.put("input_file_id", inputId);
    request.put("endpoint", "/v1/chat/completions");
    request.put("completion_window", "24h");
    return postJson("/batches", request).required("id").asText();
  }

  @Override
  public void verifyTerminalFailure(String batchId) throws IOException, InterruptedException {
    String status = getJson("/batches/" + batchId).path("status").asText();
    if (!List.of("failed", "expired", "cancelled").contains(status)) {
      throw new IllegalStateException(
          "Batch "
              + batchId
              + " is "
              + status
              + "; recovery requires failed, expired, or cancelled status");
    }
  }

  @Override
  public Map<String, String> completedDescriptions(String batchId)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + Duration.ofHours(24).toNanos();
    JsonNode batch;
    while (true) {
      batch = getJson("/batches/" + batchId);
      String status = batch.path("status").asText();
      var counts = batch.path("request_counts");
      LOGGER.info(
          "short.description.batch.progress batchId={} status={} completed={} failed={} total={}",
          batchId,
          status,
          counts.path("completed").asInt(),
          counts.path("failed").asInt(),
          counts.path("total").asInt());
      if ("completed".equals(status)) {
        break;
      }
      if (!List.of("validating", "in_progress", "finalizing", "cancelling").contains(status)) {
        throw new IllegalStateException("Batch " + batchId + " is " + status);
      }
      if (System.nanoTime() >= deadline) {
        throw new IOException(
            "Timed out waiting for batch "
                + batchId
                + "; retry import using the retained manifest");
      }
      Thread.sleep(pollInterval);
    }
    String outputFileId = batch.path("output_file_id").asText();
    if (!hasText(outputFileId)) {
      throw new IllegalStateException("Completed batch did not provide an output file");
    }
    return parseDescriptions(getText("/files/" + outputFileId + "/content"));
  }

  private int writeBatchInput(Path input, List<AudiobookRecord> books) throws IOException {
    int loaded = 0;
    try (var writer = Files.newBufferedWriter(input, StandardCharsets.UTF_8)) {
      for (List<AudiobookRecord> group : groups(books)) {
        writer.write(mapper.writeValueAsString(batchRequest(group, loaded)));
        writer.newLine();
        loaded += group.size();
      }
    }
    return loaded;
  }

  private List<List<AudiobookRecord>> groups(List<AudiobookRecord> records) {
    List<List<AudiobookRecord>> groups = new ArrayList<>();
    for (int start = 0; start < records.size(); start += BOOKS_PER_REQUEST) {
      groups.add(records.subList(start, Math.min(start + BOOKS_PER_REQUEST, records.size())));
    }
    return groups;
  }

  public ObjectNode batchRequest(List<AudiobookRecord> books, int requestNumber) {
    ObjectNode request = mapper.createObjectNode();
    request.put("custom_id", "short-description-" + requestNumber);
    request.put("method", "POST");
    request.put("url", "/v1/chat/completions");
    ObjectNode body = request.putObject("body");
    body.put("model", model);
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
    for (AudiobookRecord book : books) {
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

  public Map<String, String> parseDescriptions(String output) throws IOException {
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
                  descriptions.put(entry.getKey(), description);
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
        .timeout(requestTimeout)
        .header("Authorization", "Bearer " + apiKey);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
