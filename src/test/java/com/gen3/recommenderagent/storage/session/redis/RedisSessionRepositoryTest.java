package com.gen3.recommenderagent.storage.session.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.storage.session.SessionBusyException;
import com.gen3.recommenderagent.storage.session.SessionCommitException;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
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
  private RedisTemplate<String, Session> redisTemplate;

  private LettuceConnectionFactory connectionFactory;
  private ScheduledExecutorService lockRenewalExecutor;
  private StringRedisTemplate stringRedisTemplate;

  @BeforeEach
  void setUp() {

    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));

    connectionFactory = new LettuceConnectionFactory(config);

    connectionFactory.afterPropertiesSet();

    JacksonJsonRedisSerializer<Session> serializer =
        new JacksonJsonRedisSerializer<>(Session.class);

    redisTemplate = new RedisTemplate<>();

    redisTemplate.setConnectionFactory(connectionFactory);

    redisTemplate.setKeySerializer(new StringRedisSerializer());

    redisTemplate.setValueSerializer(serializer);

    redisTemplate.afterPropertiesSet();

    stringRedisTemplate = new StringRedisTemplate(connectionFactory);
    stringRedisTemplate.afterPropertiesSet();
    lockRenewalExecutor = Executors.newSingleThreadScheduledExecutor();
    redisSessionCache =
        new RedisSessionRepository(
            redisTemplate,
            stringRedisTemplate,
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            lockRenewalExecutor);
  }

  @AfterEach
  void closeLockRenewalExecutor() {
    lockRenewalExecutor.shutdownNow();
    connectionFactory.destroy();
  }

  // ============================================================
  // SESSION
  // ============================================================

  @Test
  void shouldSaveAndRetrieveSessionId() {

    Session session = session("session-123");

    write(session);

    Session retrieved = read("session-123", USER_ID);

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

  @Test
  void releasesTheSessionLockAfterTheOperationCompletes() {
    assertThrows(
        SessionBusyException.class,
        () ->
            redisSessionCache.update(
                "session-lock-test",
                USER_ID,
                session ->
                    redisSessionCache.update("session-lock-test", USER_ID, other -> "second")));

    assertEquals(
        "first", redisSessionCache.update("session-lock-test", USER_ID, session -> "first"));

    assertEquals(
        "second", redisSessionCache.update("session-lock-test", USER_ID, other -> "second"));
  }

  @Test
  void releaseFailureDoesNotOverrideCommittedResultsOrOriginalErrors() {
    var failingRelease = spy(stringRedisTemplate);
    org.mockito.Mockito.doThrow(
            new org.springframework.dao.DataAccessResourceFailureException("release failed"))
        .when(failingRelease)
        .execute(
            any(org.springframework.data.redis.core.script.RedisScript.class),
            any(List.class),
            org.mockito.ArgumentMatchers.<Object[]>any());
    var repository =
        new RedisSessionRepository(
            redisTemplate,
            failingRelease,
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            lockRenewalExecutor);
    assertEquals(
        "committed",
        repository.update(
            "release-failure",
            USER_ID,
            session -> {
              session.setBookCount(2);
              return "committed";
            }));
    assertEquals(2, read("release-failure", USER_ID).getBookCount());
    var original = new IllegalArgumentException("original");
    assertEquals(
        original,
        assertThrows(
            IllegalArgumentException.class,
            () ->
                repository.update(
                    "release-failure-other",
                    USER_ID,
                    session -> {
                      throw original;
                    })));
  }

  @Test
  void renewsTheLockForAStillRunningTransition() throws Exception {
    AtomicReference<Runnable> renewal = new AtomicReference<>();
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    doAnswer(
            invocation -> {
              renewal.set(invocation.getArgument(0));
              return mock(ScheduledFuture.class);
            })
        .when(scheduler)
        .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    redisSessionCache =
        new RedisSessionRepository(
            redisTemplate,
            stringRedisTemplate,
            Duration.ofSeconds(30),
            Duration.ofSeconds(1),
            scheduler);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
    try {
      Future<String> first =
          requestExecutor.submit(
              () ->
                  redisSessionCache.update(
                      "renewal-test",
                      USER_ID,
                      session -> {
                        entered.countDown();
                        await(finish);
                        return "first";
                      }));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      String lockKey = "lock:" + key("renewal-test");
      stringRedisTemplate.expire(lockKey, Duration.ofSeconds(5));
      renewal.get().run();
      Long renewedTtl = stringRedisTemplate.getExpire(lockKey, TimeUnit.MILLISECONDS);
      assertNotNull(renewedTtl);
      assertTrue(renewedTtl > 10_000, "Renewal should restore the 30-second lease");

      assertThrows(
          SessionBusyException.class,
          () -> redisSessionCache.update("renewal-test", USER_ID, session -> "second"));

      finish.countDown();
      assertEquals("first", first.get(5, TimeUnit.SECONDS));
    } finally {
      finish.countDown();
      requestExecutor.shutdownNow();
    }
  }

  @Test
  void doesNotCommitWhenTheLockOwnershipChanges() {
    assertThrows(
        SessionCommitException.class,
        () ->
            redisSessionCache.update(
                "ownership-test",
                USER_ID,
                session -> {
                  session.setBookCount(3);
                  stringRedisTemplate.opsForValue().set("lock:" + key("ownership-test"), "other");
                  return "not committed";
                }));
    assertNull(read("ownership-test", USER_ID));
    assertEquals("other", stringRedisTemplate.opsForValue().get("lock:" + key("ownership-test")));
  }

  // ============================================================
  // SESSION REQUEST
  // ============================================================

  @Test
  void shouldSaveAndRetrieveSessionRequest() {

    Session session = session("session-request-test");

    ConversationDraft request = new ConversationDraft();

    session.getActiveSearch().setSearch(ConversationDraft.toTurn(request));

    write(session);

    Session retrieved = read("session-request-test", USER_ID);

    assertNotNull(retrieved);

    assertNotNull(retrieved.getActiveSearch().getSearch());
  }

  @Test
  void shouldStoreShownBooksAsIdsOnly() {
    Session session = session("shown-book-ids-test");
    session.getActiveSearch().addShownBookIds(List.of("book-1", "book-2"));

    write(session);

    Session retrieved = read("shown-book-ids-test", USER_ID);
    assertEquals(List.of("book-1", "book-2"), retrieved.getActiveSearch().getShownBookIds());
  }

  @Test
  void shouldSaveAndRetrieveSessionPreferences() {
    Session session = session("session-preferences-test");

    SemanticQuery positiveQuery = new SemanticQuery();
    positiveQuery.setGenres(List.of("fantasy"));
    ExactCriteria mustInclude = new ExactCriteria();
    mustInclude.setLanguage("English");

    SessionPreferences preferences = new SessionPreferences();
    preferences.setPositiveSemanticQuery(positiveQuery);
    preferences.setMustInclude(mustInclude);
    session.setPreferences(preferences);

    write(session);

    Session retrieved = read("session-preferences-test", USER_ID);
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

    ConversationDraft request = new ConversationDraft();

    request.setPositiveSemanticQuery(semanticQuery);

    session.getActiveSearch().setSearch(ConversationDraft.toTurn(request));

    write(session);

    Session retrieved = read("semanticQuery-test", USER_ID);

    SemanticQuery retrievedSemanticQuery =
        retrieved.getActiveSearch().getSearch().getPositiveSemanticQuery();

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

    ConversationDraft request = new ConversationDraft();

    request.setNegativeSemanticQuery(negativeQuery);

    session.getActiveSearch().setSearch(ConversationDraft.toTurn(request));

    write(session);

    Session retrieved = read("negative-query-test", USER_ID);

    SemanticQuery retrievedQuery =
        retrieved.getActiveSearch().getSearch().getNegativeSemanticQuery();

    assertNotNull(retrievedQuery);
    assertEquals(List.of("romance"), retrievedQuery.getGenres());
  }

  // ============================================================
  // INCLUDE AND EXCLUDE FILTERS
  // ============================================================

  @Test
  void shouldSaveAndRetrieveIncludeAndExcludeFilters() {

    Session session = session("constraints-test");

    ExactCriteria mustInclude = new ExactCriteria();
    mustInclude.setDuration("under 10 hours");
    mustInclude.setLanguage("English");
    mustInclude.setPublicationYear("after 2020");
    mustInclude.setSource("NLS");
    ExactCriteria mustNotInclude = new ExactCriteria();
    mustNotInclude.setLanguage("French");
    mustNotInclude.setPublicationYear("before 2000");
    mustNotInclude.setSource("Legacy");

    ConversationDraft request = new ConversationDraft();

    request.setBookCount(5);
    request.setMustInclude(mustInclude);
    request.setMustNotInclude(mustNotInclude);

    session.getActiveSearch().setSearch(ConversationDraft.toTurn(request));

    write(session);

    Session retrieved = read("constraints-test", USER_ID);

    ConversationTurn retrievedRequest = retrieved.getActiveSearch().getSearch();
    assertEquals(5, retrievedRequest.getBookCount());
    assertEquals("under 10 hours", retrievedRequest.getMustInclude().getDuration());
    assertEquals("English", retrievedRequest.getMustInclude().getLanguage());
    assertEquals("after 2020", retrievedRequest.getMustInclude().getPublicationYear());
    assertEquals("NLS", retrievedRequest.getMustInclude().getSource());
    assertEquals("French", retrievedRequest.getMustNotInclude().getLanguage());
    assertEquals("before 2000", retrievedRequest.getMustNotInclude().getPublicationYear());
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

    ConversationDraft request = new ConversationDraft();

    request.setRecommendations(recommendations);

    session.getActiveSearch().setSearch(ConversationDraft.toTurn(request));

    write(session);

    Session retrieved = read("recommendations-test", USER_ID);

    List<Recommendation> retrievedRecommendations =
        retrieved.getActiveSearch().getSearch().getRecommendations();

    assertNotNull(retrievedRecommendations);

    assertEquals(2, retrievedRecommendations.size());

    assertEquals("book1", retrievedRecommendations.get(0).getId());
    assertEquals(1, retrievedRecommendations.get(0).getRank());
    assertEquals(10.0, retrievedRecommendations.get(0).getScore());

    assertEquals("book2", retrievedRecommendations.get(1).getId());
    assertEquals(1, retrievedRecommendations.get(1).getRank());
    assertEquals(20.0, retrievedRecommendations.get(1).getScore());
  }

  // ============================================================
  // OPTIONAL OBJECTS
  // ============================================================

  @Test
  void shouldPreserveNullOptionalObjects() {

    Session session = session("null-test");

    ConversationDraft request = new ConversationDraft();

    session.getActiveSearch().setSearch(ConversationDraft.toTurn(request));

    write(session);

    Session retrieved = read("null-test", USER_ID);

    ConversationTurn retrievedRequest = retrieved.getActiveSearch().getSearch();

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

    ConversationDraft request = new ConversationDraft();

    request.setPositiveSemanticQuery(semanticQuery);

    session.getActiveSearch().setSearch(ConversationDraft.toTurn(request));

    // First write
    write(session);

    // Modify session
    session
        .getActiveSearch()
        .getSearch()
        .getPositiveSemanticQuery()
        .setTopics(List.of("WWI", "Vietnam War"));

    // Second write
    write(session);

    // Retrieve
    Session retrieved = read(sessionId, USER_ID);

    assertNotNull(retrieved);

    assertEquals(
        List.of("WWI", "Vietnam War"),
        retrieved.getActiveSearch().getSearch().getPositiveSemanticQuery().getTopics());
  }

  @Test
  void separatesSessionsWithTheSameSessionIdForDifferentUsers() {
    Session first = session("shared-session");
    first.setUserId("user:one");
    Session second = session("shared-session");
    second.setUserId("user:two");

    write(first);
    write(second);

    assertEquals("user:one", read("shared-session", "user:one").getUserId());
    assertEquals("user:two", read("shared-session", "user:two").getUserId());
  }

  @Test
  void keepsCompositeKeysDistinctWhenIdsContainSeparators() {
    Session first = session("c");
    first.setUserId("a:b");
    Session second = session("b:c");
    second.setUserId("a");

    write(first);
    write(second);

    assertEquals("a:b", read("c", "a:b").getUserId());
    assertEquals("a", read("b:c", "a").getUserId());
  }

  @Test
  void rejectsAStaleSessionWrite() {
    redisSessionCache.update(
        "concurrent-session",
        USER_ID,
        session -> {
          session.setBookCount(1);
          return null;
        });
    assertThrows(
        SessionCommitException.class,
        () ->
            redisSessionCache.update(
                "concurrent-session",
                USER_ID,
                session -> {
                  Session external = read("concurrent-session", USER_ID);
                  external.setVersion(external.getVersion() + 1);
                  external.setBookCount(2);
                  redisTemplate.opsForValue().set(key("concurrent-session"), external);
                  session.setBookCount(3);
                  return null;
                }));
    assertEquals(2, read("concurrent-session", USER_ID).getBookCount());
  }

  @Test
  void failedChangeIsNotSavedAndReleasesTheLock() {
    redisSessionCache.update(
        "failed-change",
        USER_ID,
        session -> {
          session.setBookCount(2);
          return null;
        });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            redisSessionCache.update(
                "failed-change",
                USER_ID,
                session -> {
                  session.setBookCount(5);
                  throw new IllegalArgumentException("failed change");
                }));
    assertEquals(2, read("failed-change", USER_ID).getBookCount());
    assertEquals(2, redisSessionCache.update("failed-change", USER_ID, Session::getBookCount));
  }

  @Test
  void createsSessionAndReturnsOnlyAfterCommit() {
    String result =
        redisSessionCache.update(
            "created-session",
            USER_ID,
            session -> {
              assertEquals("created-session", session.getSessionId());
              assertEquals(USER_ID, session.getUserId());
              assertNotNull(session.getCreatedAt());
              assertEquals(0, session.getVersion());
              session.setBookCount(4);
              return "done";
            });
    assertEquals("done", result);
    Session stored = read("created-session", USER_ID);
    assertEquals(4, stored.getBookCount());
    assertEquals(1, stored.getVersion());
    assertNull(stringRedisTemplate.opsForValue().get("lock:" + key("created-session")));
  }

  @Test
  void anotherRepositoryInstanceCannotUpdateLockedSession() {
    RedisSessionRepository second = new RedisSessionRepository(redisTemplate, stringRedisTemplate);
    redisSessionCache.update(
        "shared-lock",
        USER_ID,
        session -> {
          assertThrows(
              SessionBusyException.class,
              () -> second.update("shared-lock", USER_ID, other -> null));
          return null;
        });
    second.update(
        "shared-lock",
        USER_ID,
        session -> {
          session.setBookCount(3);
          return null;
        });
    assertEquals(3, read("shared-lock", USER_ID).getBookCount());
  }

  @Test
  void sessionIdsEndingInLockDoNotCollideWithOtherSessionLocks() {
    redisSessionCache.update(
        "collision:lock",
        USER_ID,
        session -> {
          session.setBookCount(2);
          return null;
        });
    redisSessionCache.update(
        "collision",
        USER_ID,
        session -> {
          session.setBookCount(3);
          return null;
        });
    assertEquals(2, read("collision:lock", USER_ID).getBookCount());
    assertEquals(3, read("collision", USER_ID).getBookCount());
  }

  @Test
  void reportsFailedCommitWhenLockChangesAfterOwnershipCheck() {
    RedisTemplate<String, Session> interruptedTemplate = spy(redisTemplate);
    doAnswer(
            invocation -> {
              CompletableFuture.runAsync(
                      () -> stringRedisTemplate.delete("lock:" + key("lost-before-exec")))
                  .join();
              return invocation.callRealMethod();
            })
        .when(interruptedTemplate)
        .exec();
    var repository =
        new RedisSessionRepository(
            interruptedTemplate,
            stringRedisTemplate,
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            lockRenewalExecutor);

    assertThrows(
        SessionCommitException.class,
        () -> repository.update("lost-before-exec", USER_ID, session -> "not committed"));
    assertNull(read("lost-before-exec", USER_ID));
  }

  @Test
  @SuppressWarnings("unchecked")
  void renewalCannotInvalidateTheCommitTransaction() throws Exception {
    CountDownLatch watched = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    RedisTemplate<String, Session> pausedTemplate = spy(redisTemplate);
    doAnswer(
            invocation -> {
              SessionCallback<Boolean> callback = invocation.getArgument(0);
              return redisTemplate.execute(
                  new SessionCallback<Boolean>() {
                    @Override
                    public <K, V> Boolean execute(RedisOperations<K, V> operations) {
                      RedisOperations<K, V> tracked = spy(operations);
                      doAnswer(
                              watch -> {
                                watch.callRealMethod();
                                watched.countDown();
                                await(resume);
                                return null;
                              })
                          .when(tracked)
                          .watch(anyCollection());
                      return callback.execute(tracked);
                    }
                  });
            })
        .when(pausedTemplate)
        .execute(any(SessionCallback.class));
    AtomicReference<Runnable> renewal = new AtomicReference<>();
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    doAnswer(
            invocation -> {
              renewal.set(invocation.getArgument(0));
              return mock(ScheduledFuture.class);
            })
        .when(scheduler)
        .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    var repository =
        new RedisSessionRepository(
            pausedTemplate,
            stringRedisTemplate,
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            scheduler);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      Future<String> update =
          threads.submit(
              () ->
                  repository.update(
                      "renew-during-commit",
                      USER_ID,
                      session -> {
                        session.setBookCount(4);
                        return "committed";
                      }));
      assertTrue(watched.await(5, TimeUnit.SECONDS));
      CountDownLatch renewalStarted = new CountDownLatch(1);
      Future<?> renewing =
          threads.submit(
              () -> {
                renewalStarted.countDown();
                renewal.get().run();
              });
      assertTrue(renewalStarted.await(5, TimeUnit.SECONDS));
      // Renewal must wait until WATCH/EXEC finishes rather than modifying its watched key.
      assertThrows(TimeoutException.class, () -> renewing.get(200, TimeUnit.MILLISECONDS));
      resume.countDown();
      assertEquals("committed", update.get(5, TimeUnit.SECONDS));
      renewing.get(5, TimeUnit.SECONDS);
      assertEquals(4, read("renew-during-commit", USER_ID).getBookCount());
    } finally {
      resume.countDown();
      threads.shutdownNow();
    }
  }

  @Test
  void newSessionsStartWithAnEmptyQueueAndPersistBoundedQueues() {
    redisSessionCache.update(
        "bounded-batch",
        USER_ID,
        session -> {
          assertTrue(session.getActiveSearch().getPendingRecommendations().isEmpty());
          session
              .getActiveSearch()
              .setPendingRecommendations(
                  IntStream.rangeClosed(1, 25)
                      .mapToObj(i -> new Recommendation("book-" + i, i, 0.9, "Title " + i))
                      .toList());
          return null;
        });
    assertEquals(
        20, read("bounded-batch", USER_ID).getActiveSearch().getPendingRecommendations().size());
  }

  @Test
  void cachedPagesPersistAndConcurrentRequestsCannotConsumeTheSamePage() throws Exception {
    redisSessionCache.update(
        "cached-pages",
        USER_ID,
        session -> {
          ConversationDraft previous = new ConversationDraft();
          previous.setIntent(ConversationAction.RECOMMENDATION);
          session.getActiveSearch().setSearch(ConversationDraft.toTurn(previous));
          session
              .getActiveSearch()
              .setPendingRecommendations(
                  IntStream.rangeClosed(1, 20)
                      .mapToObj(
                          i ->
                              new Recommendation(
                                  "book-" + i, i, 0.9, "Title " + i, "A short summary."))
                      .toList());
          return null;
        });
    CountDownLatch consumed = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    SessionRepository paused =
        new SessionRepository() {
          @Override
          public <T, R> R update(
              String id, String user, Function<Session, T> change, Function<T, R> afterCommit) {
            return redisSessionCache.update(
                id,
                user,
                session -> {
                  T result = change.apply(session);
                  consumed.countDown();
                  await(finish);
                  return result;
                },
                afterCommit);
          }
        };
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    var first = ConversationTestSupport.transitions(paused, workflow);
    var second =
        ConversationTestSupport.transitions(
            new RedisSessionRepository(redisTemplate, stringRedisTemplate), workflow);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      ConversationDraft more = new ConversationDraft();
      more.setIntent(ConversationAction.MORE_RESULTS);
      Future<ConversationTurn> firstPage =
          worker.submit(() -> first.process("cached-pages", USER_ID, more));
      assertTrue(consumed.await(5, TimeUnit.SECONDS));
      assertThrows(SessionBusyException.class, () -> second.process("cached-pages", USER_ID, more));
      finish.countDown();
      assertEquals(
          List.of("book-1", "book-2", "book-3", "book-4", "book-5"),
          firstPage.get(5, TimeUnit.SECONDS).getRecommendations().stream()
              .map(Recommendation::getId)
              .toList());
      Session saved = read("cached-pages", USER_ID);
      assertEquals(15, saved.getActiveSearch().getPendingRecommendations().size());
      assertEquals(
          "book-6", saved.getActiveSearch().getPendingRecommendations().getFirst().getId());
      assertEquals(
          "A short summary.",
          saved.getActiveSearch().getPendingRecommendations().getFirst().getShortDescription());
      assertEquals(
          List.of("book-6", "book-7", "book-8", "book-9", "book-10"),
          second.process("cached-pages", USER_ID, more).getRecommendations().stream()
              .map(Recommendation::getId)
              .toList());
      assertEquals(10, read("cached-pages", USER_ID).getActiveSearch().getShownBookIds().size());
      verifyNoInteractions(workflow);
    } finally {
      finish.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  void leaseCoversPostCommitResponseAcrossRepositoryInstances() throws Exception {
    var generating = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var worker = Executors.newSingleThreadExecutor();
    var other = new RedisSessionRepository(redisTemplate, stringRedisTemplate);
    try {
      Future<String> response =
          worker.submit(
              () ->
                  redisSessionCache.update(
                      "response-lease",
                      USER_ID,
                      session -> {
                        session.setBookCount(2);
                        return "saved";
                      },
                      result -> {
                        generating.countDown();
                        await(release);
                        return result + " response";
                      }));
      assertTrue(generating.await(5, TimeUnit.SECONDS));
      assertEquals(2, read("response-lease", USER_ID).getBookCount());
      assertThrows(
          SessionBusyException.class,
          () -> other.update("response-lease", USER_ID, session -> null));
      assertEquals("independent", other.update("other-session", USER_ID, session -> "independent"));
      release.countDown();
      assertEquals("saved response", response.get(5, TimeUnit.SECONDS));
      assertEquals("next", other.update("response-lease", USER_ID, session -> "next"));
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  void failingResponseReleasesLeaseWithoutRollingBackCommittedState() {
    assertThrows(
        IllegalStateException.class,
        () ->
            redisSessionCache.update(
                "response-failure",
                USER_ID,
                session -> {
                  session.setBookCount(3);
                  return "saved";
                },
                result -> {
                  throw new IllegalStateException("generation failed");
                }));
    assertEquals(3, read("response-failure", USER_ID).getBookCount());
    assertEquals(
        "retry", redisSessionCache.update("response-failure", USER_ID, session -> "retry"));
  }

  private Session read(String sessionId, String userId) {
    String storageKey = "session:" + userId.length() + ":" + userId + ":" + sessionId;
    return redisTemplate.opsForValue().get(storageKey);
  }

  private void write(Session source) {
    redisSessionCache.update(
        source.getSessionId(),
        source.getUserId(),
        session -> {
          org.springframework.beans.BeanUtils.copyProperties(
              source, session, "version", "createdAt", "expiresAt");
          return null;
        });
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

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for the test transition");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for the test transition", exception);
    }
  }
}
