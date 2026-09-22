package com.gen3.recommenderagent.integration;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.engine.events.DomainEventPublisher;
import com.gen3.recommenderagent.engine.events.SessionUpdatedEvent;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.util.HashMap;
import java.util.Map;

final class RecommendationFlowTestSupport {

  private RecommendationFlowTestSupport() {}

  static DomainEventPublisher sessionPublisher(SessionRepository sessionRepository) {
    return event -> {
      if (event instanceof SessionUpdatedEvent sessionUpdatedEvent) {
        sessionRepository.updateSession(sessionUpdatedEvent.getSession());
      }
    };
  }

  static final class InMemorySessionRepository implements SessionRepository {

    private final Map<String, Session> sessions = new HashMap<>();

    @Override
    public Session getSession(String sessionId) {
      return sessions.get(sessionId);
    }

    @Override
    public void updateSession(Session session) {
      sessions.put(session.getSessionId(), session);
    }
  }
}
