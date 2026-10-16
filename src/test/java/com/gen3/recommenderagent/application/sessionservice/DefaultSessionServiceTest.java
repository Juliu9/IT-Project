package com.gen3.recommenderagent.application.sessionservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import org.junit.jupiter.api.Test;

class DefaultSessionServiceTest {

  @Test
  void createsNewSession() {
    SessionRepository repository = mock(SessionRepository.class);
    when(repository.getSession("session-1", "user-1")).thenReturn(null);
    DefaultSessionService service = new DefaultSessionService(repository);

    Session session = service.load("session-1", "user-1");
    assertEquals("session-1", session.getSessionId());
    assertEquals("user-1", session.getUserId());
  }

  @Test
  void savesACompletedSessionWithoutApplyingTransitionRules() {
    SessionRepository repository = mock(SessionRepository.class);
    Session session = new Session();
    when(repository.updateSession(session)).thenReturn(true);

    org.junit.jupiter.api.Assertions.assertTrue(
        new DefaultSessionService(repository).save(session));

    verify(repository).updateSession(session);
  }

  @Test
  void loadsExistingSessionWithoutReplacingIt() {
    SessionRepository repository = mock(SessionRepository.class);
    Session existing = new Session();
    when(repository.getSession("session-2", "user-2")).thenReturn(existing);

    Session session = new DefaultSessionService(repository).load("session-2", "user-2");

    assertSame(existing, session);
  }
}
