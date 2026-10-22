package com.gen3.recommenderagent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.session.SessionCommitException;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SessionTransitionServiceTest {

  @Test
  void commitsRecommendationsAndConversationStateInOneTransition() {
    SessionRepository sessions = mock(SessionRepository.class);
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    SessionRequest request = request(Intent.RECOMMENDATION);
    List<Recommendation> recommendations =
        List.of(
            new Recommendation("book-1", 1, 0.9, "First"),
            new Recommendation("book-2", 2, 0.8, "Second"));

    runUpdate(sessions, session);
    when(workflow.recommend(any(), eq(session))).thenReturn(recommendations);

    SessionRequest result = service(sessions, workflow).process("session-1", "user-1", request);

    assertEquals(
        recommendations.stream().map(Recommendation::getBookId).toList(),
        result.getRecommendations().stream().map(Recommendation::getBookId).toList());
    assertEquals(
        com.gen3.recommenderagent.domain.session.ConversationDecision.RECOMMENDATIONS,
        result.getDecision());
    verify(sessions).update(eq("session-1"), eq("user-1"), any());
    assertNotNull(result.getRequestId());
    assertNotNull(result.getCreatedAt());
    assertEquals(List.of("book-1", "book-2"), session.getShownBooks());
    assertEquals(1, session.getRequests().size());
    assertEquals(result.getRequestId(), session.getRequests().getFirst().getRequestId());
  }

  @Test
  void ownsNonRecommendationSessionMutations() {
    SessionRepository sessions = mock(SessionRepository.class);
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    SessionRequest request = request(Intent.CHANGE_COUNT);
    request.setBookCount(2);

    runUpdate(sessions, session);

    SessionRequest result = service(sessions, workflow).process("session-2", "user-2", request);

    assertEquals(
        com.gen3.recommenderagent.domain.session.ConversationDecision.COUNT_CHANGED,
        result.getDecision());
    verify(sessions).update(eq("session-2"), eq("user-2"), any());
    assertEquals(2, session.getBookCount());
    assertEquals(List.of(), result.getRecommendations());
    verifyNoInteractions(workflow);
  }

  @Test
  void doesNotRepeatRecommendationWorkWhenCommitFails() {
    SessionRepository sessions = mock(SessionRepository.class);
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    SessionRequest request = request(Intent.RECOMMENDATION);

    when(workflow.recommend(any(), eq(session))).thenReturn(List.of());
    when(sessions.update(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Function<Session, SessionRequest> change = invocation.getArgument(2);
              change.apply(session);
              throw new SessionCommitException();
            });

    assertThrows(
        SessionCommitException.class,
        () -> service(sessions, workflow).process("session-3", "user-3", request));

    verify(workflow).recommend(any(), eq(session));
  }

  private SessionTransitionService service(
      SessionRepository sessions, RecommendationWorkflow workflow) {
    return new SessionTransitionService(sessions, workflow);
  }

  private SessionRequest request(Intent intent) {
    SessionRequest request = new SessionRequest();
    request.setIntent(intent);
    return request;
  }

  private void runUpdate(SessionRepository sessions, Session session) {
    when(sessions.update(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Function<Session, SessionRequest> change = invocation.getArgument(2);
              return change.apply(session);
            });
  }
}
