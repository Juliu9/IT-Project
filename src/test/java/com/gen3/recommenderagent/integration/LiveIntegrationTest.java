package com.gen3.recommenderagent.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.api.RequestGateway;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Editable, opt-in end-to-end test using real OpenAI and Qdrant services.
 *
 * <p>A temporary Redis container isolates session data from shared environments. Qdrant migration
 * and user preference import remain disabled so the test does not bulk-write external data. Edit
 * {@link #RAW_TEXT} to choose the request committed and tested by GitHub Actions.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("external")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QDRANT_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QDRANT_API", matches = ".+")
class LiveIntegrationTest {

  // Edit only this value when you want to try a different live request.
  private static final String RAW_TEXT =
      "Recommend three English science fiction audiobooks.";

  private static final int REDIS_PORT = 6379;
  private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(5);

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);

  @DynamicPropertySource
  static void configureLiveServices(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    registry.add("spring.ai.openai.api-key", () -> environment("OPENAI_API_KEY", "missing"));
    registry.add("qdrant.url", () -> environment("QDRANT_URL", "http://127.0.0.1:1"));
    registry.add("qdrant.grpc-port", () -> environment("QDRANT_GRPC_PORT", "6334"));
    registry.add("qdrant.api-key", () -> environment("QDRANT_API", ""));
    registry.add("qdrant.collection", () -> environment("QDRANT_COLLECTION", "audiobooks_hybrid"));
    registry.add(
        "qdrant.user-preference-collection",
        () -> environment("QDRANT_USER_PREFERENCE_COLLECTION", "user"));
    registry.add("audiobook.candidate-retriever", () -> "qdrant");
    registry.add("audiobook.qdrant.migration.enabled", () -> "false");
    registry.add("user.preferences.import-on-startup", () -> "false");
  }

  @Autowired private RequestGateway requestGateway;

  @Autowired private SessionRepository sessionRepository;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void shouldRunConfiguredRawTextThroughCompleteLiveFlow() throws Exception {
    String rawText = RAW_TEXT;
    String sessionId = "full-live-" + UUID.randomUUID();
    String userId = "full-live-test-user";

    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(requestGateway).build();

    long startedAt = System.nanoTime();
    String response =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("X-Session-Id", sessionId)
                    .header("X-User-Id", userId)
                    .contentType(MediaType.TEXT_PLAIN)
                    .content(rawText))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long elapsedMilliseconds = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

    assertFalse(response.isBlank(), "The final response must not be blank");

    Session savedSession = awaitSavedSession(sessionId, SESSION_TIMEOUT);
    assertNotNull(savedSession, "The completed request was not saved to Docker Redis");
    assertEquals(userId, savedSession.getUserId());
    assertFalse(savedSession.getRequests().isEmpty(), "The session contains no requests");

    SessionRequest completedRequest = savedSession.getRequests().getLast();
    assertEquals(rawText, completedRequest.getRawText());
    assertNotNull(completedRequest.getRequestId());
    assertFalse(
        completedRequest.getRequestId().isBlank(), "The completed request has no request ID");
    assertNotNull(completedRequest.getIntent(), "The AI parser returned no intent");

    System.out.println("\n=== Full live integration result ===");
    System.out.println("Raw text: " + rawText);
    System.out.println("Elapsed time: " + elapsedMilliseconds + " ms");
    System.out.println("Request ID: " + completedRequest.getRequestId());
    System.out.println("Intent: " + completedRequest.getIntent());
    if (completedRequest.getRecommendations() == null
        || completedRequest.getRecommendations().isEmpty()) {
      System.out.println("Recommendations: none");
    } else {
      System.out.println("Recommendations: " + completedRequest.getRecommendations().size());
      completedRequest
          .getRecommendations()
          .forEach(
              recommendation ->
                  System.out.println(
                      "- " + recommendation.getBookId() + ": " + recommendation.getTitle()));
    }
    System.out.println("Final response: " + response);

    System.out.println("\n=== Complete SessionRequest ===");
    System.out.println(
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(completedRequest));
    System.out.println("===================================");
    System.out.println("====================================\n");
  }

  private Session awaitSavedSession(String sessionId, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      Session session = sessionRepository.getSession(sessionId);
      if (session != null) {
        return session;
      }
      Thread.sleep(100);
    }
    return null;
  }

  private static String environment(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
