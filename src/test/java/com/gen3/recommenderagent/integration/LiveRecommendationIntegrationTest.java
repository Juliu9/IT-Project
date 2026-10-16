package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Opt-in end-to-end check of the real OpenAI, Redis, and Qdrant request path. */
@Tag("external")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QDRANT_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QDRANT_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveRecommendationIntegrationTest {

  @LocalServerPort private int port;

  @Test
  void returnsARecommendationResponseFromTheRealRequestPath() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/recommendations"))
            .header("Content-Type", "text/plain")
            .header("X-Session-Id", "ci-" + UUID.randomUUID())
            .header("X-User-Id", "ci-user")
            .POST(HttpRequest.BodyPublishers.ofString("Recommend a science fiction audiobook"))
            .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isBetween(200, 299);
    assertThat(response.body()).isNotBlank();
    assertThat(response.body()).doesNotContain("Sorry, I couldn't find any recommendations");
  }
}
