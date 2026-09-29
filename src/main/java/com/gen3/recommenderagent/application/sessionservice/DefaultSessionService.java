package com.gen3.recommenderagent.application.sessionservice;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of the application session lifecycle. */
@Service
public class DefaultSessionService implements SessionService {

  private final SessionRepository sessionRepository;

  public DefaultSessionService(SessionRepository sessionRepository) {
    this.sessionRepository = sessionRepository;
  }

  @Override
  public Session load(String sessionId, String userId) {
    Session session = sessionRepository.getSession(sessionId);
    if (session == null) {
      session = new Session();
      session.setSessionId(sessionId);
      session.setUserId(userId);
      session.setCreatedAt(Instant.now());
    }
    return session;
  }

  @Override
  public void update(Session session, SessionRequest originalRequest, SessionRequest actionResult) {
    SessionRequest result = actionResult == null ? originalRequest : actionResult;
    if (result.getRawText() == null && originalRequest != null) {
      result.setRawText(originalRequest.getRawText());
    }
    if (result.getRequestId() == null) {
      result.setRequestId(UUID.randomUUID().toString());
    }
    if (result.getCreatedAt() == null) {
      result.setCreatedAt(Instant.now());
    }
    if (result.getRecommendations() == null) {
      result.setRecommendations(List.of());
    }

    session.addRequest(result);
    sessionRepository.updateSession(session);
  }
}
