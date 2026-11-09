package com.gen3.recommenderagent.storage.user.redis;

import com.gen3.recommenderagent.storage.user.model.SearchHistoryProfile;
import com.gen3.recommenderagent.storage.user.port.SearchHistoryRepository;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Keeps each user's search taste in Redis, next to the sessions (MAPA-30). */
public class RedisSearchHistoryRepository implements SearchHistoryRepository {

  private static final String KEY_PREFIX = "search-history:";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Duration expireAfter;

  public RedisSearchHistoryRepository(
      StringRedisTemplate redis, ObjectMapper objectMapper, Duration expireAfter) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.expireAfter = expireAfter;
  }

  @Override
  public Optional<SearchHistoryProfile> findByUserId(String userId) throws IOException {
    try {
      String json = redis.opsForValue().get(key(userId));
      if (json == null) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readValue(json, SearchHistoryProfile.class));
    } catch (DataAccessException | JacksonException exception) {
      throw new IOException("Could not load search history", exception);
    }
  }

  @Override
  public void save(SearchHistoryProfile profile) throws IOException {
    try {
      String json = objectMapper.writeValueAsString(profile);
      // Zero means keep it until it's deleted, so RealThing can choose their own limit.
      if (expireAfter.isZero()) {
        redis.opsForValue().set(key(profile.userId()), json);
      } else {
        redis.opsForValue().set(key(profile.userId()), json, expireAfter);
      }
    } catch (DataAccessException | JacksonException exception) {
      throw new IOException("Could not save search history", exception);
    }
  }

  @Override
  public void delete(String userId) throws IOException {
    try {
      redis.delete(key(userId));
    } catch (DataAccessException exception) {
      throw new IOException("Could not delete search history", exception);
    }
  }

  private String key(String userId) {
    return KEY_PREFIX + userId;
  }
}
