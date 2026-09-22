package com.gen3.recommenderagent.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.api.RequestGateway;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.sessioncache.SessionCache;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Opt-in end-to-end test for the real external services.
 *
 * <p>This test uses OpenAI and the configured staging Solr instance. Redis and PostgreSQL run in
 * temporary Docker containers so the test cannot alter shared session or user-profile data.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("external")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SOLR_URL", matches = ".+")
class FinalLiveIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

  @Container @ServiceConnection
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configureExternalServices(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.openai.api-key", () -> environment("OPENAI_API_KEY", "missing"));
    registry.add("ai.api-key", () -> environment("OPENAI_API_KEY", "missing"));
    registry.add("solr.url", () -> environment("SOLR_URL", "http://127.0.0.1:1/solr"));
    registry.add("solr.collection", () -> environment("SOLR_COLLECTION", "combinedbooks"));
    registry.add("solr.username", () -> environment("SOLR_USERNAME", ""));
    registry.add("solr.password", () -> environment("SOLR_PASSWORD", ""));
  }

  @Autowired private RequestGateway requestGateway;

  @Autowired private SessionCache sessionCache;

  @Test
  void shouldRunFromRawTextThroughOpenAiAndStagingSolr() throws Exception {
    String rawText = "Recommend science fiction audiobooks.";
    String sessionId = "live-" + UUID.randomUUID();
    String userId = "live-test-user";

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

    assertNotNull(response);
    assertFalse(response.isBlank());

    Session savedSession = awaitSavedSession(sessionId, Duration.ofSeconds(5));
    assertNotNull(savedSession, "The completed request was not saved to Docker Redis");
    assertEquals(userId, savedSession.getUserId());
    assertFalse(savedSession.getRequests().isEmpty());

    SessionRequest parsedRequest = savedSession.getRequests().getLast();
    assertEquals(rawText, parsedRequest.getRawText());
    assertEquals(Intent.NEW_RECOMMENDATION, parsedRequest.getIntent());
    assertNotNull(parsedRequest.getQuery());
    assertNotNull(parsedRequest.getRecommendations());
    assertFalse(
        parsedRequest.getRecommendations().isEmpty(),
        "Staging Solr did not return any recommendations");

    System.out.println("\n=== Final live integration result ===");
    System.out.println("Elapsed time: " + elapsedMilliseconds + " ms");
    System.out.println("Intent: " + parsedRequest.getIntent());
    System.out.println("Recommendations: " + parsedRequest.getRecommendations());
    System.out.println("Final response: " + response);
    System.out.println("=====================================\n");
  }

  private Session awaitSavedSession(String sessionId, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);

    while (Instant.now().isBefore(deadline)) {
      Session session = sessionCache.getSession(sessionId);
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
