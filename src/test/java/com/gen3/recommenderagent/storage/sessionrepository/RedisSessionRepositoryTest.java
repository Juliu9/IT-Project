package com.gen3.recommenderagent.storage.sessionrepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gen3.recommenderagent.domain.session.Filter;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.sessionrepository.redis.RedisSessionRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedisSessionRepositoryTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private RedisSessionRepository redisSessionCache;

  private LettuceConnectionFactory connectionFactory;

  @BeforeEach
  void setUp() {

    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));

    connectionFactory = new LettuceConnectionFactory(config);

    connectionFactory.afterPropertiesSet();

    JacksonJsonRedisSerializer<Session> serializer =
        new JacksonJsonRedisSerializer<>(Session.class);

    RedisTemplate<String, Session> redisTemplate = new RedisTemplate<>();

    redisTemplate.setConnectionFactory(connectionFactory);

    redisTemplate.setKeySerializer(new StringRedisSerializer());

    redisTemplate.setValueSerializer(serializer);

    redisTemplate.afterPropertiesSet();

    redisSessionCache = new RedisSessionRepository(redisTemplate);
  }

  // ============================================================
  // SESSION
  // ============================================================

  @Test
  void shouldSaveAndRetrieveSessionId() {

    Session session = new Session();

    session.setSessionId("session-123");

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("session-123");

    assertNotNull(retrieved);

    assertEquals("session-123", retrieved.getSessionId());
  }

  // ============================================================
  // SESSION REQUEST
  // ============================================================

  @Test
  void shouldSaveAndRetrieveSessionRequest() {

    Session session = new Session();

    session.setSessionId("session-request-test");

    SessionRequest request = new SessionRequest();

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("session-request-test");

    assertNotNull(retrieved);

    assertNotNull(retrieved.getRequests());

    assertEquals(1, retrieved.getRequests().size());
  }

  // ============================================================
  // QUERY
  // ============================================================

  @Test
  void shouldSaveAndRetrieveQuery() {

    Session session = new Session();

    session.setSessionId("semanticQuery-test");

    SemanticQuery semanticQuery = new SemanticQuery();

    semanticQuery.setTopics(List.of("WWI", "history"));

    semanticQuery.setGenres(List.of("historical"));

    semanticQuery.setAuthors(List.of("Author One"));

    semanticQuery.setKeywords(List.of("war", "Europe"));

    SessionRequest request = new SessionRequest();

    request.setPositiveSemanticQuery(semanticQuery);

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("semanticQuery-test");

    SemanticQuery retrievedSemanticQuery =
        retrieved.getRequests().get(0).getPositiveSemanticQuery();

    assertNotNull(retrievedSemanticQuery);

    assertEquals(List.of("WWI", "history"), retrievedSemanticQuery.getTopics());

    assertEquals(List.of("historical"), retrievedSemanticQuery.getGenres());

    assertEquals(List.of("Author One"), retrievedSemanticQuery.getAuthors());

    assertEquals(List.of("war", "Europe"), retrievedSemanticQuery.getKeywords());
  }

  // ============================================================
  // NEGATIVE SEMANTIC QUERY
  // ============================================================

  @Test
  void shouldSaveAndRetrieveNegativeSemanticQuery() {

    Session session = new Session();

    session.setSessionId("negative-query-test");

    SemanticQuery negativeQuery = new SemanticQuery();
    negativeQuery.setGenres(List.of("romance"));

    SessionRequest request = new SessionRequest();

    request.setNegativeSemanticQuery(negativeQuery);

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("negative-query-test");

    SemanticQuery retrievedQuery = retrieved.getRequests().get(0).getNegativeSemanticQuery();

    assertNotNull(retrievedQuery);
    assertEquals(List.of("romance"), retrievedQuery.getGenres());
  }

  // ============================================================
  // FILTERS
  // ============================================================

  @Test
  void shouldSaveAndRetrieveFilters() {

    Session session = new Session();

    session.setSessionId("constraints-test");

    Filter filter = new Filter();

    filter.setCount(5);
    filter.setDuration("under 10 hours");
    filter.setLanguage("English");

    SessionRequest request = new SessionRequest();

    request.setFilter(filter);

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("constraints-test");

    Filter retrievedFilter = retrieved.getRequests().get(0).getFilter();

    assertNotNull(retrievedFilter);

    assertEquals(5, retrievedFilter.getCount());

    assertEquals("under 10 hours", retrievedFilter.getDuration());

    assertEquals("English", retrievedFilter.getLanguage());
  }

  // ============================================================
  // RECOMMENDATIONS
  // ============================================================

  @Test
  void shouldSaveAndRetrieveRecommendations() {

    Session session = new Session();

    session.setSessionId("recommendations-test");

    List<Recommendation> recommendations =
        List.of(
            new Recommendation("book1", 1, 10.0, "Book One"),
            new Recommendation("book2", 1, 20.0, "Book Two"));

    SessionRequest request = new SessionRequest();

    request.setRecommendations(recommendations);

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("recommendations-test");

    List<Recommendation> retrievedRecommendations =
        retrieved.getRequests().get(0).getRecommendations();

    assertNotNull(retrievedRecommendations);

    assertEquals(2, retrievedRecommendations.size());

    assertEquals("book1", retrievedRecommendations.get(0).getBookId());
    assertEquals(1, retrievedRecommendations.get(0).getRank());
    assertEquals(10.0, retrievedRecommendations.get(0).getScore());

    assertEquals("book2", retrievedRecommendations.get(1).getBookId());
    assertEquals(1, retrievedRecommendations.get(1).getRank());
    assertEquals(20.0, retrievedRecommendations.get(1).getScore());
  }

  // ============================================================
  // OPTIONAL OBJECTS
  // ============================================================

  @Test
  void shouldPreserveNullOptionalObjects() {

    Session session = new Session();

    session.setSessionId("null-test");

    SessionRequest request = new SessionRequest();

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("null-test");

    SessionRequest retrievedRequest = retrieved.getRequests().get(0);

    assertNull(retrievedRequest.getPositiveSemanticQuery());
    assertNull(retrievedRequest.getNegativeSemanticQuery());

    assertNull(retrievedRequest.getFilter());
  }

  // ============================================================
  // UPDATE
  // ============================================================

  @Test
  void shouldUpdateExistingSession() {

    String sessionId = "update-test";

    Session session = new Session();

    session.setSessionId(sessionId);

    SemanticQuery semanticQuery = new SemanticQuery();

    semanticQuery.setTopics(List.of("WWI"));

    SessionRequest request = new SessionRequest();

    request.setPositiveSemanticQuery(semanticQuery);

    session.setRequests(List.of(request));

    // First write
    redisSessionCache.updateSession(session);

    // Modify session
    semanticQuery.setTopics(List.of("WWI", "Vietnam War"));

    // Second write
    redisSessionCache.updateSession(session);

    // Retrieve
    Session retrieved = redisSessionCache.getSession(sessionId);

    assertNotNull(retrieved);

    assertEquals(
        List.of("WWI", "Vietnam War"),
        retrieved.getRequests().get(0).getPositiveSemanticQuery().getTopics());
  }
}
