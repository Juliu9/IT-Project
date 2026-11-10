package com.gen3.recommenderagent.storage.user.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gen3.recommenderagent.storage.user.model.SearchHistoryProfile;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class RedisSearchHistoryRepositoryTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private static final Instant UPDATED_AT = Instant.parse("2026-11-09T10:00:00Z");

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate stringRedisTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    stringRedisTemplate = new StringRedisTemplate(connectionFactory);
    stringRedisTemplate.afterPropertiesSet();
  }

  @AfterEach
  void tearDown() {
    connectionFactory.destroy();
  }

  @Test
  void savesAndLoadsAProfile() throws IOException {
    var repository = repositoryThatKeepsProfiles();
    repository.save(profile("saves-user"));

    var loaded = repository.findByUserId("saves-user").orElseThrow();

    assertArrayEquals(new float[] {0.6f, 0.8f}, loaded.tasteVector());
    assertEquals(4, loaded.searchCount());
    assertEquals(UPDATED_AT, loaded.updatedAt());
  }

  @Test
  void userWithNoSearchesHasNoProfile() throws IOException {
    assertTrue(repositoryThatKeepsProfiles().findByUserId("new-user").isEmpty());
  }

  @Test
  void deleteRemovesTheProfile() throws IOException {
    var repository = repositoryThatKeepsProfiles();
    repository.save(profile("delete-user"));

    repository.delete("delete-user");

    assertTrue(repository.findByUserId("delete-user").isEmpty());
  }

  @Test
  void deletingAMissingProfileIsFine() throws IOException {
    repositoryThatKeepsProfiles().delete("never-searched");
  }

  @Test
  void keepsTheProfileWhenExpiryIsOff() throws IOException {
    repositoryThatKeepsProfiles().save(profile("keep-user"));

    // Redis reports -1 for a key that never expires.
    assertEquals(-1L, stringRedisTemplate.getExpire("search-history:keep-user"));
  }

  @Test
  void expiresTheProfileWhenExpiryIsOn() throws IOException {
    new RedisSearchHistoryRepository(stringRedisTemplate, objectMapper, Duration.ofDays(30))
        .save(profile("expire-user"));

    long secondsLeft = stringRedisTemplate.getExpire("search-history:expire-user");

    assertTrue(secondsLeft > 0 && secondsLeft <= Duration.ofDays(30).toSeconds());
  }

  @Test
  void storeFailureBecomesAnIoException() {
    var unreachable =
        new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1));
    unreachable.afterPropertiesSet();
    var repository =
        new RedisSearchHistoryRepository(
            new StringRedisTemplate(unreachable), objectMapper, Duration.ZERO);

    assertThrows(IOException.class, () -> repository.findByUserId("any-user"));
    unreachable.destroy();
  }

  private RedisSearchHistoryRepository repositoryThatKeepsProfiles() {
    return new RedisSearchHistoryRepository(stringRedisTemplate, objectMapper, Duration.ZERO);
  }

  private SearchHistoryProfile profile(String userId) {
    return new SearchHistoryProfile(userId, new float[] {0.6f, 0.8f}, 4, UPDATED_AT);
  }
}
