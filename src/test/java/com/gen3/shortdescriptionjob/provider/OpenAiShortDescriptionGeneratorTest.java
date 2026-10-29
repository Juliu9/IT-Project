package com.gen3.shortdescriptionjob.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.catalogue.model.AudiobookRecord;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiShortDescriptionGeneratorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final List<AudiobookRecord> books =
      List.of(new AudiobookRecord("book", "s", "Title", List.of(), "Description"));
  private HttpServer server;
  private OpenAiShortDescriptionGenerator generator;
  private String response;
  private int status = 200;
  private final AtomicInteger calls = new AtomicInteger();
  private String path;
  private String authorization;
  private String body;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          path = exchange.getRequestURI().getPath();
          authorization = exchange.getRequestHeaders().getFirst("Authorization");
          body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
    generator =
        new OpenAiShortDescriptionGenerator(
            mapper,
            HttpClient.newHttpClient(),
            "test-key",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
            "summary-model");
    response = completion("{\"book\":\"Summary.\"}", "stop");
  }

  private String completion(String content, String reason) {
    var result = mapper.createObjectNode();
    var choice = result.putArray("choices").addObject();
    choice.put("finish_reason", reason);
    choice.putObject("message").put("content", content);
    return result.toString();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void sendsRegularChatRequestWithoutUploadingFilesOrSubmittingBatches() throws Exception {
    assertThat(generator.generate(books)).containsEntry("book", "Summary.");
    assertThat(calls.get()).isEqualTo(1);
    assertThat(path).isEqualTo("/v1/chat/completions");
    assertThat(authorization).isEqualTo("Bearer test-key");
    var request = mapper.readTree(body);
    assertThat(request.path("model").asText()).isEqualTo("summary-model");
    assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
    assertThat(request.path("messages").path(1).path("content").asText())
        .contains("book", "Title", "Description");
  }

  @Test
  void refusesMalformedDuplicateNonTextAndTruncatedOutput() {
    for (String content :
        List.of(
            "",
            "not json",
            "[]",
            "{\"book\":1}",
            "{\"book\":\" \"}",
            "{\"book\":\"a\",\"book\":\"b\"}")) {
      response = completion(content, "stop");
      assertThatThrownBy(() -> generator.generate(books)).isInstanceOf(java.io.IOException.class);
    }
    response = completion("{\"book\":\"Summary\"}", "length");
    assertThatThrownBy(() -> generator.generate(books))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("did not finish");
  }

  @Test
  void doesNotRetryAuthenticationErrorsOrExposeResponseBody() {
    status = 401;
    response = "sensitive provider response";
    assertThatThrownBy(() -> generator.generate(books))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("401")
        .hasMessageNotContaining("sensitive");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void retriesRateLimitsOnlyUpToThreeAttempts() {
    status = 429;
    assertThatThrownBy(() -> generator.generate(books))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("429");
    assertThat(calls.get()).isEqualTo(3);
  }
}
