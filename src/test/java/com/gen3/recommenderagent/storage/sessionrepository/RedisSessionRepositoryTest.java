package com.gen3.recommenderagent.storage.sessionrepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.MustNotInclude;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.sessionrepository.redis.RedisSessionRepository;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

  private static final String USER_ID = "user-1";

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

    Session session = session("session-123");

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("session-123", USER_ID);

    assertNotNull(retrieved);

    assertEquals("session-123", retrieved.getSessionId());
    assertNotNull(retrieved.getExpiresAt());
    Long ttl =
        connectionFactory
            .getConnection()
            .keyCommands()
            .ttl(key("session-123").getBytes(), TimeUnit.SECONDS);
    assertNotNull(ttl);
    assertTrue(ttl > 0);
  }

  // ============================================================
  // SESSION REQUEST
  // ============================================================

  @Test
  void shouldSaveAndRetrieveSessionRequest() {

    Session session = session("session-request-test");

    SessionRequest request = new SessionRequest();

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("session-request-test", USER_ID);

    assertNotNull(retrieved);

    assertNotNull(retrieved.getRequests());

    assertEquals(1, retrieved.getRequests().size());
  }

  @Test
  void shouldStoreShownBooksAsIdsOnly() {
    Session session = session("shown-book-ids-test");
    session.addRecommendationIds(List.of("book-1", "book-2"));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("shown-book-ids-test", USER_ID);
    assertEquals(List.of("book-1", "book-2"), retrieved.getShownBooks());
  }

  @Test
  void shouldSaveAndRetrieveSessionPreferences() {
    Session session = session("session-preferences-test");

    SemanticQuery positiveQuery = new SemanticQuery();
    positiveQuery.setGenres(List.of("fantasy"));
    MustInclude mustInclude = new MustInclude();
    mustInclude.setLanguage("English");

    SessionPreferences preferences = new SessionPreferences();
    preferences.setPositiveSemanticQuery(positiveQuery);
    preferences.setMustInclude(mustInclude);
    session.setPreferences(preferences);

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("session-preferences-test", USER_ID);
    assertNotNull(retrieved.getPreferences());
    assertEquals(
        List.of("fantasy"), retrieved.getPreferences().getPositiveSemanticQuery().getGenres());
    assertEquals("English", retrieved.getPreferences().getMustInclude().getLanguage());
  }

  // ============================================================
  // QUERY
  // ============================================================

  @Test
  void shouldSaveAndRetrieveQuery() {

    Session session = session("semanticQuery-test");

    SemanticQuery semanticQuery = new SemanticQuery();

    semanticQuery.setTopics(List.of("WWI", "history"));

    semanticQuery.setGenres(List.of("historical"));

    semanticQuery.setAuthors(List.of("Author One"));

    semanticQuery.setKeywords(List.of("war", "Europe"));

    SessionRequest request = new SessionRequest();

    request.setPositiveSemanticQuery(semanticQuery);

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("semanticQuery-test", USER_ID);

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

    Session session = session("negative-query-test");

    SemanticQuery negativeQuery = new SemanticQuery();
    negativeQuery.setGenres(List.of("romance"));

    SessionRequest request = new SessionRequest();

    request.setNegativeSemanticQuery(negativeQuery);

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("negative-query-test", USER_ID);

    SemanticQuery retrievedQuery = retrieved.getRequests().get(0).getNegativeSemanticQuery();

    assertNotNull(retrievedQuery);
    assertEquals(List.of("romance"), retrievedQuery.getGenres());
  }

  // ============================================================
  // INCLUDE AND EXCLUDE FILTERS
  // ============================================================

  @Test
  void shouldSaveAndRetrieveIncludeAndExcludeFilters() {

    Session session = session("constraints-test");

    MustInclude mustInclude = new MustInclude();
    mustInclude.setDuration("under 10 hours");
    mustInclude.setLanguage("English");
    mustInclude.setSource("NLS");
    MustNotInclude mustNotInclude = new MustNotInclude();
    mustNotInclude.setLanguage("French");
    mustNotInclude.setSource("Legacy");

    SessionRequest request = new SessionRequest();

    request.setBookCount(5);
    request.setMustInclude(mustInclude);
    request.setMustNotInclude(mustNotInclude);

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("constraints-test", USER_ID);

    SessionRequest retrievedRequest = retrieved.getRequests().get(0);
    assertEquals(5, retrievedRequest.getBookCount());
    assertEquals("under 10 hours", retrievedRequest.getMustInclude().getDuration());
    assertEquals("English", retrievedRequest.getMustInclude().getLanguage());
    assertEquals("NLS", retrievedRequest.getMustInclude().getSource());
    assertEquals("French", retrievedRequest.getMustNotInclude().getLanguage());
    assertEquals("Legacy", retrievedRequest.getMustNotInclude().getSource());
  }

  // ============================================================
  // RECOMMENDATIONS
  // ============================================================

  @Test
  void shouldSaveAndRetrieveRecommendations() {

    Session session = session("recommendations-test");

    List<Recommendation> recommendations =
        List.of(
            new Recommendation("book1", 1, 10.0, "Book One"),
            new Recommendation("book2", 1, 20.0, "Book Two"));

    SessionRequest request = new SessionRequest();

    request.setRecommendations(recommendations);

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("recommendations-test", USER_ID);

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

    Session session = session("null-test");

    SessionRequest request = new SessionRequest();

    session.setRequests(List.of(request));

    redisSessionCache.updateSession(session);

    Session retrieved = redisSessionCache.getSession("null-test", USER_ID);

    SessionRequest retrievedRequest = retrieved.getRequests().get(0);

    assertNull(retrievedRequest.getPositiveSemanticQuery());
    assertNull(retrievedRequest.getNegativeSemanticQuery());

    assertNull(retrievedRequest.getMustInclude());
    assertNull(retrievedRequest.getMustNotInclude());
  }

  // ============================================================
  // UPDATE
  // ============================================================

  @Test
  void shouldUpdateExistingSession() {

    String sessionId = "update-test";

    Session session = session(sessionId);

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
    Session retrieved = redisSessionCache.getSession(sessionId, USER_ID);

    assertNotNull(retrieved);

    assertEquals(
        List.of("WWI", "Vietnam War"),
        retrieved.getRequests().get(0).getPositiveSemanticQuery().getTopics());
  }

  @Test
  void separatesSessionsWithTheSameSessionIdForDifferentUsers() {
    Session first = session("shared-session");
    first.setUserId("user:one");
    Session second = session("shared-session");
    second.setUserId("user:two");

    redisSessionCache.updateSession(first);
    redisSessionCache.updateSession(second);

    assertEquals(
        "user:one", redisSessionCache.getSession("shared-session", "user:one").getUserId());
    assertEquals(
        "user:two", redisSessionCache.getSession("shared-session", "user:two").getUserId());
  }

  @Test
  void keepsCompositeKeysDistinctWhenIdsContainSeparators() {
    Session first = session("c");
    first.setUserId("a:b");
    Session second = session("b:c");
    second.setUserId("a");

    redisSessionCache.updateSession(first);
    redisSessionCache.updateSession(second);

    assertEquals("a:b", redisSessionCache.getSession("c", "a:b").getUserId());
    assertEquals("a", redisSessionCache.getSession("b:c", "a").getUserId());
  }

  @Test
  void rejectsAStaleSessionWrite() {
    Session initial = session("concurrent-session");
    assertTrue(redisSessionCache.updateSession(initial));
    Session current = redisSessionCache.getSession("concurrent-session", USER_ID);
    Session stale = redisSessionCache.getSession("concurrent-session", USER_ID);

    current.setBookCount(2);
    assertTrue(redisSessionCache.updateSession(current));
    stale.setBookCount(3);
    assertFalse(redisSessionCache.updateSession(stale));

    assertEquals(2, redisSessionCache.getSession("concurrent-session", USER_ID).getBookCount());
  }

  private Session session(String sessionId) {
    Session session = new Session();
    session.setSessionId(sessionId);
    session.setUserId(USER_ID);
    return session;
  }

  private String key(String sessionId) {
    return "session:" + USER_ID.length() + ":" + USER_ID + ":" + sessionId;
  }
}
