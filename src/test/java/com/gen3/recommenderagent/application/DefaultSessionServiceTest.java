package com.gen3.recommenderagent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultSessionServiceTest {

  @Test
  void createsNewContextAndPersistsActionOutcome() {
    SessionRepository repository = mock(SessionRepository.class);
    when(repository.getSession("session-1")).thenReturn(null);
    DefaultSessionService service = new DefaultSessionService(repository);

    SessionContext context = service.load("session-1", "user-1");
    SessionRequest request = new SessionRequest();
    Recommendation recommendation = new Recommendation("book-1", 1, 1.0, "Book One");
    request.setRecommendations(List.of(recommendation));

    service.update(context, request, request);

    Session session = context.getSession();
    assertEquals("session-1", session.getSessionId());
    assertEquals("user-1", session.getUserId());
    assertNotNull(request.getRequestId());
    assertNotNull(request.getCreatedAt());
    assertSame(request, session.getRequests().getFirst());
    assertSame(recommendation, session.getShownBooks().getFirst());
    verify(repository).updateSession(session);
  }

  @Test
  void loadsExistingSessionWithoutReplacingIt() {
    SessionRepository repository = mock(SessionRepository.class);
    Session existing = new Session();
    when(repository.getSession("session-2")).thenReturn(existing);

    SessionContext context = new DefaultSessionService(repository).load("session-2", "user-2");

    assertSame(existing, context.getSession());
  }
}
