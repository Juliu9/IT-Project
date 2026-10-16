package com.gen3.recommenderagent.application.sessionservice;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.time.Instant;
import java.util.function.Supplier;
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
    Session session = sessionRepository.getSession(sessionId, userId);
    if (session == null) {
      session = new Session();
      session.setSessionId(sessionId);
      session.setUserId(userId);
      session.setCreatedAt(Instant.now());
    }
    return session;
  }

  @Override
  public boolean save(Session session) {
    return sessionRepository.updateSession(session);
  }

  @Override
  public <T> T withSessionLock(String sessionId, String userId, Supplier<T> operation) {
    return sessionRepository.withSessionLock(sessionId, userId, operation);
  }
}
