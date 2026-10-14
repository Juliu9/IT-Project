package com.gen3.recommenderagent.storage.sessionrepository.redis;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.redis.core.RedisTemplate;
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
  public void updateSession(Session session) {
    session.setExpiresAt(Instant.now().plus(SESSION_TTL));
    redisTemplate
        .opsForValue()
        .set(sessionKey(session.getSessionId(), session.getUserId()), session, SESSION_TTL);
  }

  private String sessionKey(String sessionId, String userId) {
    String user = Objects.requireNonNull(userId, "userId must not be null");
    String session = Objects.requireNonNull(sessionId, "sessionId must not be null");
    return "session:" + user.length() + ":" + user + ":" + session;
  }
}
