package com.gen3.recommenderagent.integration;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.api.RequestExceptionHandler;
import com.gen3.recommenderagent.api.RequestGateway;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.time.Duration;
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

/** Live negative-input test for a whitespace-only recommendation request. */
@SpringBootTest
@Testcontainers
@Tag("external")
class LiveIntegrationTest {

  private static final String INITIAL_RAW_TEXT = "   ";
  private static final String EXPECTED_ERROR =
      "I didn't catch that. What would you like to listen to?";
  private static final int REDIS_PORT = 6379;

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

  @Test
  void shouldRejectWhitespaceBeforeCreatingRecommendationState() throws Exception {
    String sessionId = "full-live-" + UUID.randomUUID();
    String userId = "full-live-test-user";
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(requestGateway)
            .setControllerAdvice(new RequestExceptionHandler())
            .build();

    long startedAt = System.nanoTime();
    String response =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("X-Session-Id", sessionId)
                    .header("X-User-Id", userId)
                    .contentType(MediaType.TEXT_PLAIN)
                    .content(INITIAL_RAW_TEXT))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(EXPECTED_ERROR))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long elapsedMilliseconds = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

    assertNull(
        sessionRepository.getSession(sessionId, userId),
        "A whitespace-only request must not create or update a Redis session");

    System.out.println("\n=== TC-02 blank-input integration result ===");
    System.out.println("Raw text: <three spaces>");
    System.out.println("HTTP status: 400");
    System.out.println("Error response: " + response);
    System.out.println("Elapsed time: " + elapsedMilliseconds + " ms");
    System.out.println("Session saved: false");
    System.out.println("============================================\n");
  }

  private static String environment(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
