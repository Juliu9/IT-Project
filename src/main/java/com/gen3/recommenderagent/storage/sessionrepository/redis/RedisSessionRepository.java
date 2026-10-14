package com.gen3.recommenderagent.storage.sessionrepository.redis;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Repository;

@Repository
public class RedisSessionRepository implements SessionRepository {

  private static final Duration SESSION_TTL = Duration.ofHours(24);

  private final RedisTemplate<String, Session> redisTemplate;

  public RedisSessionRepository(RedisTemplate<String, Session> redisTemplate) {

    this.redisTemplate = redisTemplate;
  }

  @Override
  public Session getSession(String sessionId, String userId) {
    return redisTemplate.opsForValue().get(sessionKey(sessionId, userId));
  }

  @Override
  public boolean updateSession(Session session) {
    String key = sessionKey(session.getSessionId(), session.getUserId());
    return Boolean.TRUE.equals(
        redisTemplate.execute(
            new SessionCallback<Boolean>() {
              @Override
              public <K, V> Boolean execute(RedisOperations<K, V> operations) {
                RedisOperations<String, Session> sessionOperations = sessionOperations(operations);
                sessionOperations.watch(key);
                Session existing = sessionOperations.opsForValue().get(key);
                if (existing == null
                    ? session.getVersion() != 0
                    : existing.getVersion() != session.getVersion()) {
                  sessionOperations.unwatch();
                  return false;
                }
                session.setExpiresAt(Instant.now().plus(SESSION_TTL));
                session.setVersion(session.getVersion() + 1);
                sessionOperations.multi();
                sessionOperations.opsForValue().set(key, session, SESSION_TTL);
                return sessionOperations.exec() != null;
              }
            }));
  }

  @SuppressWarnings("unchecked")
  private RedisOperations<String, Session> sessionOperations(RedisOperations<?, ?> operations) {
    return (RedisOperations<String, Session>) operations;
  }

  private String sessionKey(String sessionId, String userId) {
    String user = Objects.requireNonNull(userId, "userId must not be null");
    String session = Objects.requireNonNull(sessionId, "sessionId must not be null");
    return "session:" + user.length() + ":" + user + ":" + session;
  }
}
