package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
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
  @Autowired private SessionRepository sessions;

  @Test
  void returnsARecommendationResponseFromTheRealRequestPath() throws Exception {
    String sessionId = "ci-" + UUID.randomUUID();
    String userId = "ci-user";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/recommendations"))
            .header("Content-Type", "text/plain")
            .header("X-Session-Id", sessionId)
            .header("X-User-Id", userId)
            .POST(HttpRequest.BodyPublishers.ofString("Recommend a science fiction audiobook"))
            .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isBetween(200, 299);
    assertThat(response.body()).isNotBlank();
    List<Recommendation> recommendations =
        sessions.update(
            sessionId,
            userId,
            session ->
                session.getRequests().stream()
                    .flatMap(saved -> saved.getRecommendations().stream())
                    .toList());
    assertThat(recommendations).hasSizeBetween(1, 5);
    assertThat(recommendations)
        .extracting(Recommendation::getBookId)
        .doesNotHaveDuplicates()
        .allSatisfy(id -> assertThat(id).isNotBlank());
    for (var book : recommendations) {
      assertThat(book.getTitle()).isNotBlank();
      String title = spokenText(book.getTitle());
      assertThat(title).isNotBlank();
      assertThat(spokenText(response.body())).contains(title);
    }
  }

  private String spokenText(String text) {
    return text.replace("$b", "").replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
  }
}
