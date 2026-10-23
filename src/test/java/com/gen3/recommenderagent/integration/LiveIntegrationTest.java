package com.gen3.recommenderagent.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.api.RequestExceptionHandler;
import com.gen3.recommenderagent.api.RequestGateway;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

/** Live tests for repeating recommendations with and without session history. */
@SpringBootTest
@Testcontainers
@Tag("external")
class LiveIntegrationTest {

  private static final String INITIAL_RAW_TEXT = "Recommend three science fiction audiobooks.";
  private static final String REPEAT_RAW_TEXT = "Please repeat the most recent recommendations.";
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
    registry.add("qdrant.api-key", () -> environment("QDRANT_API_KEY", ""));
    registry.add("qdrant.collection", () -> environment("QDRANT_COLLECTION", "audiobooks_hybrid"));
    registry.add(
        "qdrant.user-preference-collection",
        () -> environment("QDRANT_USER_PREFERENCE_COLLECTION", "user"));
    registry.add("audiobook.database", () -> "qdrant");
    registry.add("audiobook.qdrant.migration.enabled", () -> "false");
    registry.add("user.preferences.import-on-startup", () -> "false");
  }

  @Autowired private RequestGateway requestGateway;

  @Autowired private SessionRepository sessionRepository;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void shouldRepeatTheMostRecentRecommendationsInTheSameSession() throws Exception {
    String sessionId = "full-live-" + UUID.randomUUID();
    String userId = "full-live-test-user";
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(requestGateway)
            .setControllerAdvice(new RequestExceptionHandler())
            .build();

    TurnResult firstTurn =
        submitTurn(mockMvc, sessionId, userId, 1, INITIAL_RAW_TEXT, Intent.RECOMMENDATION, 3);
    assertFalse(
        firstTurn.recommendationIds().isEmpty(),
        "The first request returned no audiobook recommendations to repeat");
    TurnResult repeatTurn =
        submitTurn(mockMvc, sessionId, userId, 2, REPEAT_RAW_TEXT, Intent.REPEAT, 3);

    Session finalSession = awaitSavedSession(sessionId, userId, SESSION_TIMEOUT);
    assertNotNull(finalSession, "The final session was not saved to Docker Redis");
    System.out.println("\n=== TC-03 repeat-most-recent integration result ===");
    System.out.println("Session ID: " + sessionId);
    printTurn(firstTurn);
    printTurn(repeatTurn);
    System.out.println("All shown audiobook IDs: " + finalSession.getShownBooks());
    System.out.println("=======================================================\n");

    assertEquals(
        firstTurn.recommendationIds(),
        repeatTurn.recommendationIds(),
        "The repeated list did not match the most recent recommendation list");
    assertTrue(
        finalSession.getShownBooks().containsAll(firstTurn.recommendationIds()),
        "The session lost audiobook IDs after repeating the recommendation list");
  }

  @Test
  void shouldExplainThatThereIsNothingToRepeatInANewSession() throws Exception {
    String sessionId = "full-live-" + UUID.randomUUID();
    String userId = "full-live-test-user";
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(requestGateway)
            .setControllerAdvice(new RequestExceptionHandler())
            .build();

    TurnResult result =
        submitTurn(mockMvc, sessionId, userId, 1, REPEAT_RAW_TEXT, Intent.REPEAT, 3);
    Session finalSession = awaitSavedSession(sessionId, userId, SESSION_TIMEOUT);

    System.out.println("\n=== TC-04 repeat-without-history integration result ===");
    System.out.println("Session ID: " + sessionId);
    printTurn(result);
    System.out.println("All shown audiobook IDs: " + finalSession.getShownBooks());
    System.out.println("=======================================================\n");

    assertTrue(
        result.recommendationIds().isEmpty(),
        "The system invented recommendations in a new session with no history");
    assertTrue(
        explainsNothingToRepeat(result.response()),
        "The response did not clearly explain that there were no previous recommendations to repeat: "
            + result.response());
    assertTrue(
        finalSession.getShownBooks() == null || finalSession.getShownBooks().isEmpty(),
        "The repeat request added shown books in a new session with no history");
  }

  private TurnResult submitTurn(
      MockMvc mockMvc,
      String sessionId,
      String userId,
      int turnNumber,
      String rawText,
      Intent expectedIntent,
      int maximumRecommendations)
      throws Exception {
    assertFalse(rawText.isBlank(), "Turn " + turnNumber + " raw text must not be blank");

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
    Session savedSession = awaitSavedSession(sessionId, userId, SESSION_TIMEOUT);
    assertNotNull(
        savedSession, "Turn " + turnNumber + " was not saved to the Docker Redis session");
    assertEquals(userId, savedSession.getUserId());
    assertFalse(savedSession.getRequests().isEmpty(), "The session contains no requests");

    SessionRequest completedRequest = savedSession.getRequests().getLast();
    assertNotNull(completedRequest.getRequestId());
    assertFalse(
        completedRequest.getRequestId().isBlank(), "The completed request has no request ID");
    if (expectedIntent != null) {
      assertEquals(
          expectedIntent,
          completedRequest.getIntent(),
          "Turn " + turnNumber + " was classified with the wrong intent");
    }

    List<String> recommendationIds = recommendationIds(completedRequest);
    assertTrue(
        recommendationIds.size() <= maximumRecommendations,
        "Turn "
            + turnNumber
            + " returned more than "
            + maximumRecommendations
            + " recommendations");

    return new TurnResult(
        turnNumber, rawText, elapsedMilliseconds, response, completedRequest, recommendationIds);
  }

  private List<String> recommendationIds(SessionRequest request) {
    if (request.getRecommendations() == null) {
      return List.of();
    }
    return request.getRecommendations().stream()
        .filter(recommendation -> recommendation != null)
        .map(Recommendation::getBookId)
        .filter(bookId -> bookId != null && !bookId.isBlank())
        .toList();
  }

  private boolean explainsNothingToRepeat(String response) {
    String normalized = response == null ? "" : response.toLowerCase();
    return normalized.contains("no previous")
        || normalized.contains("no earlier")
        || normalized.contains("nothing to repeat")
        || normalized.contains("no recommendations to repeat");
  }

  private void printTurn(TurnResult turn) throws Exception {
    System.out.println("\n--- Turn " + turn.turnNumber() + " ---");
    System.out.println("Raw text: " + turn.rawText());
    System.out.println("Elapsed time: " + turn.elapsedMilliseconds() + " ms");
    System.out.println("Request ID: " + turn.completedRequest().getRequestId());
    System.out.println("Intent: " + turn.completedRequest().getIntent());
    if (turn.completedRequest().getRecommendations() == null
        || turn.completedRequest().getRecommendations().isEmpty()) {
      System.out.println("Recommendations: none");
    } else {
      System.out.println("Recommendations: " + turn.completedRequest().getRecommendations().size());
      turn.completedRequest()
          .getRecommendations()
          .forEach(
              recommendation ->
                  System.out.println(
                      "- "
                          + recommendation.getBookId()
                          + ": "
                          + recommendation.getTitle()
                          + " | Reason: "
                          + recommendation.getShortDescription()));
    }
    System.out.println("Final response: " + turn.response());
    System.out.println("Complete SessionRequest:");
    System.out.println(
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(turn.completedRequest()));
  }

  private Session awaitSavedSession(String sessionId, String userId, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      Session session = sessionRepository.update(sessionId, userId, savedSession -> savedSession);
      if (!session.getRequests().isEmpty()) {
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

  private record TurnResult(
      int turnNumber,
      String rawText,
      long elapsedMilliseconds,
      String response,
      SessionRequest completedRequest,
      List<String> recommendationIds) {}
}
