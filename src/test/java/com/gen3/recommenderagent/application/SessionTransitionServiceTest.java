package com.gen3.recommenderagent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.sessionservice.SessionCommitException;
import com.gen3.recommenderagent.application.sessionservice.SessionService;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationWorkflow;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SessionTransitionServiceTest {

  @Test
  void commitsRecommendationsAndConversationStateInOneTransition() {
    SessionService sessions = mock(SessionService.class);
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    SessionRequest request = request(Intent.RECOMMENDATION);
    List<Recommendation> recommendations =
        List.of(
            new Recommendation("book-1", 1, 0.9, "First"),
            new Recommendation("book-2", 2, 0.8, "Second"));

    runWithinSessionLock(sessions);
    when(sessions.load("session-1", "user-1")).thenReturn(session);
    when(workflow.recommend(any(), eq(session))).thenReturn(recommendations);
    when(sessions.save(session)).thenReturn(true);

    SessionRequest result = service(sessions, workflow).process("session-1", "user-1", request);

    assertSame(recommendations, result.getRecommendations());
    assertNotNull(result.getRequestId());
    assertNotNull(result.getCreatedAt());
    assertEquals(List.of("book-1", "book-2"), session.getShownBooks());
    assertEquals(1, session.getRequests().size());
    assertEquals(result.getRequestId(), session.getRequests().getFirst().getRequestId());
    InOrder order = inOrder(sessions, workflow);
    order.verify(sessions).load("session-1", "user-1");
    order.verify(workflow).recommend(any(), eq(session));
    order.verify(sessions).save(session);
  }

  @Test
  void ownsNonRecommendationSessionMutations() {
    SessionService sessions = mock(SessionService.class);
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    SessionRequest request = request(Intent.CHANGE_COUNT);
    request.setBookCount(2);

    runWithinSessionLock(sessions);
    when(sessions.load("session-2", "user-2")).thenReturn(session);
    when(sessions.save(session)).thenReturn(true);

    SessionRequest result = service(sessions, workflow).process("session-2", "user-2", request);

    assertEquals(2, session.getBookCount());
    assertEquals(List.of(), result.getRecommendations());
    verifyNoInteractions(workflow);
    verify(sessions).save(session);
  }

  @Test
  void doesNotRepeatRecommendationWorkWhenCommitFails() {
    SessionService sessions = mock(SessionService.class);
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    SessionRequest request = request(Intent.RECOMMENDATION);

    runWithinSessionLock(sessions);
    when(sessions.load("session-3", "user-3")).thenReturn(session);
    when(workflow.recommend(any(), eq(session))).thenReturn(List.of());
    when(sessions.save(session)).thenReturn(false);

    assertThrows(
        SessionCommitException.class,
        () -> service(sessions, workflow).process("session-3", "user-3", request));

    verify(workflow).recommend(any(), eq(session));
    verify(sessions).save(session);
  }

  private SessionTransitionService service(
      SessionService sessions, RecommendationWorkflow workflow) {
    return new SessionTransitionService(sessions, new RequestContextMerger(), workflow);
  }

  private SessionRequest request(Intent intent) {
    SessionRequest request = new SessionRequest();
    request.setIntent(intent);
    return request;
  }

  @SuppressWarnings("unchecked")
  private void runWithinSessionLock(SessionService sessions) {
    when(sessions.withSessionLock(any(), any(), any()))
        .thenAnswer(invocation -> ((Supplier<SessionRequest>) invocation.getArgument(2)).get());
  }
}
