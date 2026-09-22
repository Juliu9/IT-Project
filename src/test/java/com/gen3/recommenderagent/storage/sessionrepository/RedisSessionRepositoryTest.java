package com.gen3.recommenderagent.storage.sessionrepository;

import com.gen3.recommenderagent.domain.session.*;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Testcontainers
class RedisSessionRepositoryTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    private RedisSessionRepository redisSessionCache;

    private LettuceConnectionFactory connectionFactory;

    @BeforeEach
    void setUp() {

        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(
                        redis.getHost(),
                        redis.getMappedPort(6379)
                );

        connectionFactory =
                new LettuceConnectionFactory(config);

        connectionFactory.afterPropertiesSet();

        JacksonJsonRedisSerializer<Session> serializer =
                new JacksonJsonRedisSerializer<>(Session.class);

        RedisTemplate<String, Session> redisTemplate =
                new RedisTemplate<>();

        redisTemplate.setConnectionFactory(connectionFactory);

        redisTemplate.setKeySerializer(
                new StringRedisSerializer()
        );

        redisTemplate.setValueSerializer(serializer);

        redisTemplate.afterPropertiesSet();

        redisSessionCache =
                new RedisSessionRepository(redisTemplate);
    }


    // ============================================================
    // SESSION
    // ============================================================

    @Test
    void shouldSaveAndRetrieveSessionId() {

        Session session = new Session();

        session.setSessionId("session-123");

        redisSessionCache.updateSession(session);

        Session retrieved =
                redisSessionCache.getSession("session-123");

        assertNotNull(retrieved);

        assertEquals(
                "session-123",
                retrieved.getSessionId()
        );
    }


    // ============================================================
    // SESSION REQUEST
    // ============================================================

    @Test
    void shouldSaveAndRetrieveSessionRequest() {

        Session session = new Session();

        session.setSessionId("session-request-test");

        SessionRequest request =
                new SessionRequest();

        session.setRequests(
                List.of(request)
        );

        redisSessionCache.updateSession(session);

        Session retrieved =
                redisSessionCache.getSession(
                        "session-request-test"
                );

        assertNotNull(retrieved);

        assertNotNull(
                retrieved.getRequests()
        );

        assertEquals(
                1,
                retrieved.getRequests().size()
        );
    }


    // ============================================================
    // QUERY
    // ============================================================

    @Test
    void shouldSaveAndRetrieveQuery() {

        Session session = new Session();

        session.setSessionId("query-test");

        Query query = new Query();

        query.setTopics(
                List.of("WWI", "history")
        );

        query.setGenres(
                List.of("historical")
        );

        query.setAuthors(
                List.of("Author One")
        );

        query.setKeywords(
                List.of("war", "Europe")
        );

        SessionRequest request =
                new SessionRequest();

        request.setQuery(query);

        session.setRequests(
                List.of(request)
        );

        redisSessionCache.updateSession(session);

        Session retrieved =
                redisSessionCache.getSession(
                        "query-test"
                );

        Query retrievedQuery =
                retrieved
                        .getRequests()
                        .get(0)
                        .getQuery();

        assertNotNull(retrievedQuery);

        assertEquals(
                List.of("WWI", "history"),
                retrievedQuery.getTopics()
        );

        assertEquals(
                List.of("historical"),
                retrievedQuery.getGenres()
        );

        assertEquals(
                List.of("Author One"),
                retrievedQuery.getAuthors()
        );

        assertEquals(
                List.of("war", "Europe"),
                retrievedQuery.getKeywords()
        );
    }


    // ============================================================
    // PREFERENCES
    // ============================================================

    @Test
    void shouldSaveAndRetrievePreferences() {

        Session session = new Session();

        session.setSessionId("preferences-test");

        Preferences preferences =
                new Preferences();

        preferences.setInclude(
                List.of("non-fiction", "history")
        );

        preferences.setExclude(
                List.of("romance")
        );

        SessionRequest request =
                new SessionRequest();

        request.setPreferences(preferences);

        session.setRequests(
                List.of(request)
        );

        redisSessionCache.updateSession(session);

        Session retrieved =
                redisSessionCache.getSession(
                        "preferences-test"
                );

        Preferences retrievedPreferences =
                retrieved
                        .getRequests()
                        .get(0)
                        .getPreferences();

        assertNotNull(retrievedPreferences);

        assertEquals(
                List.of("non-fiction", "history"),
                retrievedPreferences.getInclude()
        );

        assertEquals(
                List.of("romance"),
                retrievedPreferences.getExclude()
        );
    }


    // ============================================================
    // CONSTRAINTS
    // ============================================================

    @Test
    void shouldSaveAndRetrieveConstraints() {

        Session session = new Session();

        session.setSessionId("constraints-test");

        Constraints constraints =
                new Constraints();

        constraints.setCount(5);
        constraints.setDuration("under 10 hours");
        constraints.setLanguage("English");

        SessionRequest request =
                new SessionRequest();

        request.setConstraints(constraints);

        session.setRequests(
                List.of(request)
        );

        redisSessionCache.updateSession(session);

        Session retrieved =
                redisSessionCache.getSession(
                        "constraints-test"
                );

        Constraints retrievedConstraints =
                retrieved
                        .getRequests()
                        .get(0)
                        .getConstraints();

        assertNotNull(retrievedConstraints);

        assertEquals(
                5,
                retrievedConstraints.getCount()
        );

        assertEquals(
                "under 10 hours",
                retrievedConstraints.getDuration()
        );

        assertEquals(
                "English",
                retrievedConstraints.getLanguage()
        );
    }


    // ============================================================
    // FEEDBACK
    // ============================================================

    @Test
    void shouldSaveAndRetrieveFeedback() {

        Session session = new Session();

        session.setSessionId("feedback-test");

        Feedback feedback =
                new Feedback();

        feedback.setType("DISLIKE");
        feedback.setReason(
                "Too slow paced"
        );

        SessionRequest request =
                new SessionRequest();

        request.setFeedback(feedback);

        session.setRequests(
                List.of(request)
        );

        redisSessionCache.updateSession(session);

        Session retrieved =
                redisSessionCache.getSession(
                        "feedback-test"
                );

        Feedback retrievedFeedback =
                retrieved
                        .getRequests()
                        .get(0)
                        .getFeedback();

        assertNotNull(retrievedFeedback);

        assertEquals(
                "DISLIKE",
                retrievedFeedback.getType()
        );

        assertEquals(
                "Too slow paced",
                retrievedFeedback.getReason()
        );
    }


    // ============================================================
    // REFERENCES
    // ============================================================

    @Test
    void shouldSaveAndRetrieveReferences() {

        Session session = new Session();

        session.setSessionId("references-test");

        BookReference bookReference =
                new BookReference();

        bookReference.setBookId("book-123");

        SessionRequest request =
                new SessionRequest();

        request.setBookReference(bookReference);

        session.setRequests(
                List.of(request)
        );

        redisSessionCache.updateSession(session);

        Session retrieved =
                redisSessionCache.getSession(
                        "references-test"
                );

        BookReference retrievedReferences =
                retrieved
                        .getRequests()
                        .get(0)
                        .getBookReference();

        assertNotNull(retrievedReferences);

        assertEquals(
                "book-123",
                retrievedReferences.getBookId()
        );
    }


    // ============================================================
    // RECOMMENDATIONS
    // ============================================================

    @Test
    void shouldSaveAndRetrieveRecommendations() {

        Session session = new Session();

        session.setSessionId(
                "recommendations-test"
        );

        List <Recommendation> recommendations =
            List.of(
                    new Recommendation("book1", 1, 10.0, "Book One"),
                    new Recommendation("book2", 1, 20.0, "Book Two")
            );

        SessionRequest request =
                new SessionRequest();

        request.setRecommendations(
                recommendations
        );

        session.setRequests(
                List.of(request)
        );

        redisSessionCache.updateSession(session);

        Session retrieved =
                redisSessionCache.getSession(
                        "recommendations-test"
                );

        List <Recommendation> retrievedRecommendations =
                retrieved
                        .getRequests()
                        .get(0)
                        .getRecommendations();

        assertNotNull(
                retrievedRecommendations
        );


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

        SessionRequest request =
                new SessionRequest();

        session.setRequests(
                List.of(request)
        );

        redisSessionCache.updateSession(session);

        Session retrieved =
                redisSessionCache.getSession(
                        "null-test"
                );

        SessionRequest retrievedRequest =
                retrieved
                        .getRequests()
                        .get(0);

        assertNull(
                retrievedRequest.getQuery()
        );

        assertNull(
                retrievedRequest.getPreferences()
        );

        assertNull(
                retrievedRequest.getConstraints()
        );

        assertNull(
                retrievedRequest.getFeedback()
        );

        assertNull(
                retrievedRequest.getBookReference()
        );
    }


    // ============================================================
    // UPDATE
    // ============================================================

    @Test
    void shouldUpdateExistingSession() {

        String sessionId = "update-test";

        Session session = new Session();

        session.setSessionId(sessionId);

        Query query = new Query();

        query.setTopics(
                List.of("WWI")
        );

        SessionRequest request =
                new SessionRequest();

        request.setQuery(query);

        session.setRequests(
                List.of(request)
        );

        // First write
        redisSessionCache.updateSession(session);

        // Modify session
        query.setTopics(
                List.of(
                        "WWI",
                        "Vietnam War"
                )
        );

        // Second write
        redisSessionCache.updateSession(session);

        // Retrieve
        Session retrieved =
                redisSessionCache.getSession(
                        sessionId
                );

        assertNotNull(retrieved);

        assertEquals(
                List.of(
                        "WWI",
                        "Vietnam War"
                ),
                retrieved
                        .getRequests()
                        .get(0)
                        .getQuery()
                        .getTopics()
        );
    }
}