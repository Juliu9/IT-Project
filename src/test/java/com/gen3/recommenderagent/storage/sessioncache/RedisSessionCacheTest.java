package com.gen3.recommenderagent.storage.sessioncache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gen3.recommenderagent.domain.session.Session;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;

import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
class RedisSessionCacheTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    @Test
    void shouldSaveAndRetrieveSessionSuccessfully() {

        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(
                        redis.getHost(),
                        redis.getMappedPort(6379)
                );

        LettuceConnectionFactory connectionFactory =
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

        RedisSessionCache redisSessionCache =
                new RedisSessionCache(redisTemplate);

        // Arrange
        String sessionId = "isolated-test-id-999";

        Session session = new Session();
        session.setSessionId(sessionId);

        // Act
        redisSessionCache.updateSession(session);

        Session retrievedSession =
                redisSessionCache.getSession(sessionId);

        // Assert
        assertNotNull(retrievedSession);

        assertEquals(
                sessionId,
                retrievedSession.getSessionId()
        );

        connectionFactory.destroy();
    }
}