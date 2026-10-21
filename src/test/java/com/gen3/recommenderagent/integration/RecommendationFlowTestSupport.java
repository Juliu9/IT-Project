package com.gen3.recommenderagent.integration;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import com.gen3.recommenderagent.storage.sessionrepository.redis.SessionBusyException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

final class RecommendationFlowTestSupport {

  private RecommendationFlowTestSupport() {}

  static final class InMemorySessionRepository implements SessionRepository {

    private final Map<SessionKey, Session> sessions = new ConcurrentHashMap<>();

    public Session getSession(String sessionId, String userId) {
      return sessions.get(new SessionKey(sessionId, userId));
    }

    public boolean updateSession(Session session) {
      sessions.put(new SessionKey(session.getSessionId(), session.getUserId()), session);
      return true;
    }

    private final Set<SessionKey> locked = ConcurrentHashMap.newKeySet();

    @Override
    public <T> T update(String sessionId, String userId, Function<Session, T> change) {
      SessionKey key = new SessionKey(sessionId, userId);
      if (!locked.add(key)) {
        throw new SessionBusyException();
      }
      try {
        Session existing = sessions.get(key);
        Session session =
            existing == null
                ? new Session()
                : new ObjectMapper().convertValue(existing, Session.class);
        if (existing == null) {
          session.setSessionId(sessionId);
          session.setUserId(userId);
          session.setCreatedAt(Instant.now());
        }
        T result = change.apply(session);
        session.setVersion(session.getVersion() + 1);
        session.setExpiresAt(Instant.now().plus(Duration.ofHours(24)));
        sessions.put(key, new ObjectMapper().convertValue(session, Session.class));
        return result;
      } finally {
        locked.remove(key);
      }
    }

    private record SessionKey(String sessionId, String userId) {}
  }
}
