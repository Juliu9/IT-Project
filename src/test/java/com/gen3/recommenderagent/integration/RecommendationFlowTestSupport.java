package com.gen3.recommenderagent.integration;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.util.HashMap;
import java.util.Map;

final class RecommendationFlowTestSupport {

  private RecommendationFlowTestSupport() {}

  static final class InMemorySessionRepository implements SessionRepository {

    private final Map<SessionKey, Session> sessions = new HashMap<>();

    @Override
    public Session getSession(String sessionId, String userId) {
      return sessions.get(new SessionKey(sessionId, userId));
    }

    @Override
    public void updateSession(Session session) {
      sessions.put(new SessionKey(session.getSessionId(), session.getUserId()), session);
    }

    private record SessionKey(String sessionId, String userId) {}
  }
}
