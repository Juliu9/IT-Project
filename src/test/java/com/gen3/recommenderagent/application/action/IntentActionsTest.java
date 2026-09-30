package com.gen3.recommenderagent.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationWorkflow;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentActionsTest {

  private final RequestContextMerger requestContextMerger = new RequestContextMerger();

  @Test
  void recommendationActionAppliesPreferencesAndDelegatesToWorkflow() {
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    SessionRequest preferenceUpdate = requestWithTopics("space opera");
    new UpdatePreferencesAction(requestContextMerger).execute(preferenceUpdate, session);
    SessionRequest request = requestWithTopics("mystery");
    List<Recommendation> recommendations =
        List.of(new Recommendation("book-1", 1, 1.0, "Book One"));
    when(workflow.recommend(request, session)).thenReturn(recommendations);

    SessionRequest result =
        new RecommendationAction(workflow, requestContextMerger).execute(request, session);

    assertSame(request, result);
    assertEquals(List.of("space opera", "mystery"), result.getPositiveSemanticQuery().getTopics());
    assertSame(recommendations, result.getRecommendations());
    verify(workflow).recommend(request, session);
  }

  @Test
  void refineActionCombinesLatestSearchWithNewCriteria() {
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    session.addRequest(requestWithTopics("science fiction"));
    SessionRequest refinement = requestWithTopics("first contact");
    when(workflow.recommend(refinement, session)).thenReturn(List.of());

    new RefineAction(workflow, requestContextMerger).execute(refinement, session);

    assertEquals(
        List.of("science fiction", "first contact"),
        refinement.getPositiveSemanticQuery().getTopics());
    verify(workflow).recommend(refinement, session);
  }

  @Test
  void moreResultsActionReusesLatestSearch() {
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    Session session = new Session();
    session.setBookCount(2);
    SessionRequest previous = requestWithTopics("history");
    previous.setBookCount(4);
    session.addRequest(previous);
    SessionRequest request = new SessionRequest();
    when(workflow.recommend(request, session)).thenReturn(List.of());

    new MoreResultsAction(workflow, requestContextMerger).execute(request, session);

    assertEquals(List.of("history"), request.getPositiveSemanticQuery().getTopics());
    assertEquals(2, request.getBookCount());
    verify(workflow).recommend(request, session);
  }

  @Test
  void lightweightActionsMutateOnlyTheirOwnedSessionState() {
    Session session = new Session();
    session.addRequest(requestWithTopics("history"));

    SessionRequest countRequest = new SessionRequest();
    countRequest.setBookCount(2);
    new ChangeCountAction().execute(countRequest, session);
    assertEquals(2, session.getBookCount());
    assertTrue(countRequest.getRecommendations().isEmpty());

    SessionRequest preferenceRequest = requestWithTopics("cozy");
    new UpdatePreferencesAction(requestContextMerger).execute(preferenceRequest, session);
    assertEquals(List.of("cozy"), session.getPreferences().getPositiveSemanticQuery().getTopics());

    SessionRequest noOpRequest = new SessionRequest();
    new NoOpAction().execute(noOpRequest, session);
    assertTrue(noOpRequest.getRecommendations().isEmpty());

    SessionRequest clearRequest = new SessionRequest();
    new ClearHistoryAction().execute(clearRequest, session);
    assertTrue(session.getRequests().isEmpty());
    assertTrue(clearRequest.getRecommendations().isEmpty());
    assertEquals(List.of("cozy"), session.getPreferences().getPositiveSemanticQuery().getTopics());
  }

  private SessionRequest requestWithTopics(String... topics) {
    SemanticQuery query = new SemanticQuery();
    query.setTopics(List.of(topics));
    SessionRequest request = new SessionRequest();
    request.setPositiveSemanticQuery(query);
    return request;
  }
}
