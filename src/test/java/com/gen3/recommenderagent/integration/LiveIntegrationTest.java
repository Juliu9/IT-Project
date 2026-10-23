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
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

/** Live two-turn test for updating preferences and requesting personalised recommendations. */
@SpringBootTest
@Testcontainers
@Tag("external")
class LiveIntegrationTest {

  private static final String INITIAL_RAW_TEXT =
      "Can you give me five english sifi books?";
  private static final String FOLLOW_UP_RAW_TEXT =
      "can you give me five more?";
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
    registry.add("audiobook.candidate-retriever", () -> "qdrant");
    registry.add("audiobook.qdrant.migration.enabled", () -> "false");
    registry.add("user.preferences.import-on-startup", () -> "false");
  }

  @Autowired private RequestGateway requestGateway;

  @Autowired private SessionRepository sessionRepository;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void shouldUseUpdatedPreferencesForRecommendationsInTheSameLiveSession() throws Exception {
    String sessionId = "full-live-" + UUID.randomUUID();
    String userId = "full-live-test-user";
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(requestGateway)
            .setControllerAdvice(new RequestExceptionHandler())
            .build();

    TurnResult firstTurn =
        submitTurn(mockMvc, sessionId, userId, 2, INITIAL_RAW_TEXT, Intent.UPDATE_PREFERENCES, 5);
    assertTrue(
        firstTurn.recommendationIds().isEmpty(),
        "Updating preferences unexpectedly returned audiobook recommendations");

    Session preferenceSession = awaitSavedSession(sessionId, userId, SESSION_TIMEOUT);
    assertNotNull(preferenceSession, "The updated preferences were not saved to Docker Redis");
    assertSavedEnglishScienceFictionPreferences(preferenceSession);

    TurnResult secondTurn =
        submitTurn(mockMvc, sessionId, userId, 2, FOLLOW_UP_RAW_TEXT, Intent.RECOMMENDATION, 5);
    assertFalse(
        secondTurn.recommendationIds().isEmpty(),
        "The personalised request returned no audiobook recommendations");
    assertTrue(
        secondTurn.completedRequest().isPersonalised(),
        "The recommendation request was not marked as personalised");
    assertRequestUsesEnglishScienceFictionPreferences(secondTurn.completedRequest());
    assertTrue(
        secondTurn.completedRequest().getRecommendations().stream()
            .allMatch(
                recommendation ->
                    recommendation.getShortDescription() != null
                        && !recommendation.getShortDescription().isBlank()),
        "At least one recommendation has no reason");

    Session finalSession = awaitSavedSession(sessionId, userId, SESSION_TIMEOUT);
    assertNotNull(finalSession, "The final session was not saved to Docker Redis");
    assertTrue(
        finalSession.getShownBooks().containsAll(secondTurn.recommendationIds()),
        "The session did not retain all recommended audiobook IDs");

    System.out.println("\n=== TC-01 personalised recommendation integration result ===");
    System.out.println("Session ID: " + sessionId);
    printTurn(firstTurn);
    printTurn(secondTurn);
    System.out.println("Saved session preferences:");
    System.out.println(
        objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(finalSession.getPreferences()));
    System.out.println("All shown audiobook IDs: " + finalSession.getShownBooks());
    System.out.println("=========================================================\n");
  }

  private void assertSavedEnglishScienceFictionPreferences(Session session) {
    SessionPreferences preferences = session.getPreferences();
    assertNotNull(preferences, "The session contains no saved preferences");
    assertTrue(
        hasEnglishLanguage(preferences), "The saved session preferences do not contain English");
    assertTrue(
        hasScienceFiction(preferences.getPositiveSemanticQuery()),
        "The saved session preferences do not contain science fiction");
  }

  private void assertRequestUsesEnglishScienceFictionPreferences(SessionRequest request) {
    assertNotNull(request.getMustInclude(), "The personalised request has no exact filters");
    assertTrue(
        "english".equalsIgnoreCase(request.getMustInclude().getLanguage()),
        "The personalised request did not use the saved English preference");
    assertTrue(
        hasScienceFiction(request.getPositiveSemanticQuery()),
        "The personalised request did not use the saved science-fiction preference");
  }

  private boolean hasEnglishLanguage(SessionPreferences preferences) {
    return preferences.getMustInclude() != null
        && "english".equalsIgnoreCase(preferences.getMustInclude().getLanguage());
  }

  private boolean hasScienceFiction(SemanticQuery query) {
    if (query == null) {
      return false;
    }
    String values =
        java.util.stream.Stream.of(query.getGenres(), query.getTopics(), query.getKeywords())
            .filter(list -> list != null)
            .flatMap(List::stream)
            .filter(value -> value != null)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .reduce("", (left, right) -> left + " " + right);
    return values.contains("science") && values.contains("fiction");
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
    assertEquals(
        expectedIntent,
        completedRequest.getIntent(),
        "Turn " + turnNumber + " was classified with the wrong intent");

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
      Session session = sessionRepository.getSession(sessionId, userId);
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

  private record TurnResult(
      int turnNumber,
      String rawText,
      long elapsedMilliseconds,
      String response,
      SessionRequest completedRequest,
      List<String> recommendationIds) {}
}
