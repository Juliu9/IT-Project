package com.gen3.recommenderagent.application.action;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationWorkflow;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentActionsTest {

  @Test
  void recommendationActionDelegatesToHeavyWorkflow() {
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    SessionRequest request = new SessionRequest();
    Session session = new Session();
    List<Recommendation> recommendations =
        List.of(new Recommendation("book-1", 1, 1.0, "Book One"));
    org.mockito.Mockito.when(workflow.recommend(request, session)).thenReturn(recommendations);

    SessionRequest result = new RecommendationAction(workflow).execute(request, session);

    assertSame(request, result);
    assertSame(recommendations, result.getRecommendations());
    verify(workflow).recommend(request, session);
  }

  @Test
  void lightweightActionsDoNotInvokeARecommendationWorkflow() {
    Session session = new Session();
    session.addRequest(new SessionRequest());
    SessionRequest request = new SessionRequest();
    assertSame(request, new RefineAction().execute(request, session));
    assertSame(request, new MoreResultsAction().execute(request, session));
    assertSame(request, new ChangeCountAction().execute(request, session));
    assertSame(request, new UpdatePreferencesAction().execute(request, session));
    assertSame(request, new NoOpAction().execute(request, session));
    assertNull(request.getRecommendations());

    SessionRequest clearRequest = new SessionRequest();
    new ClearHistoryAction().execute(clearRequest, session);
    assertTrue(session.getRequests().isEmpty());
  }
}
