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

import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.storage.session.SessionCommitException;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SessionTransitionServiceTest {

  @Test
  void commitsRecommendationsAndConversationDispatcherInOneTransition() {
    SessionRepository sessions = mock(SessionRepository.class);
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    UserIntent request = intent(ConversationAction.RECOMMENDATION);
    List<Recommendation> recommendations =
        List.of(
            new Recommendation("book-1", 1, 0.9, "First"),
            new Recommendation("book-2", 2, 0.8, "Second"));

    runUpdate(sessions, session);
    when(workflow.recommend(any(), eq(session))).thenReturn(recommendations);

    ConversationTurn result =
        service(sessions, workflow)
            .process("session-1", "user-1", "recommend books", request, Function.identity());

    assertEquals(
        recommendations.stream().map(Recommendation::getId).toList(),
        result.getRecommendations().stream().map(Recommendation::getId).toList());
    assertEquals(ConversationDecision.RECOMMENDATIONS, result.getDecision());
    verify(sessions).update(eq("session-1"), eq("user-1"), any(), any());
    assertNotNull(result.getRequestId());
    assertNotNull(result.getCreatedAt());
    assertEquals(List.of("book-1", "book-2"), session.getActiveSearch().getShownBookIds());
    assertNotNull(session.getActiveSearch().getSearch());
    assertEquals(result.getRequestId(), session.getActiveSearch().getSearch().getRequestId());
  }

  @Test
  void ownsNonRecommendationSessionMutations() {
    SessionRepository sessions = mock(SessionRepository.class);
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    UserIntent request = intent(ConversationAction.CHANGE_COUNT);
    request.setBookCount(java.math.BigInteger.valueOf(2));

    runUpdate(sessions, session);

    ConversationTurn result =
        service(sessions, workflow)
            .process("session-2", "user-2", "set count to 2", request, Function.identity());

    assertEquals(ConversationDecision.COUNT_CHANGED, result.getDecision());
    verify(sessions).update(eq("session-2"), eq("user-2"), any(), any());
    assertEquals(2, session.getBookCount());
    assertEquals(List.of(), result.getRecommendations());
    verifyNoInteractions(workflow);
  }

  @Test
  void doesNotRepeatRecommendationWorkWhenCommitFails() {
    SessionRepository sessions = mock(SessionRepository.class);
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    UserIntent request = intent(ConversationAction.RECOMMENDATION);

    when(workflow.recommend(any(), eq(session))).thenReturn(List.of());
    when(sessions.update(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Function<Session, ConversationTurn> change = invocation.getArgument(2);
              change.apply(session);
              throw new SessionCommitException();
            });

    assertThrows(
        SessionCommitException.class,
        () ->
            service(sessions, workflow)
                .process(
                    "session-3",
                    "user-3",
                    "recommend books",
                    request,
                    turn -> {
                      throw new AssertionError("A failed commit must not generate a response");
                    }));

    verify(workflow).recommend(any(), eq(session));
  }

  private SessionTransitionService service(
      SessionRepository sessions, RecommendationWorkflow workflow) {
    return ConversationTestSupport.transitions(sessions, workflow);
  }

  private UserIntent intent(ConversationAction intent) {
    UserIntent request = new UserIntent();
    request.setIntent(intent);
    return request;
  }

  private void runUpdate(SessionRepository sessions, Session session) {
    when(sessions.update(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Function<Session, ConversationTurn> change = invocation.getArgument(2);
              Function<ConversationTurn, Object> afterCommit = invocation.getArgument(3);
              return afterCommit.apply(change.apply(session));
            });
  }
}
