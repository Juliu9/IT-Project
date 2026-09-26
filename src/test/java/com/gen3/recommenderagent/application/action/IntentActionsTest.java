package com.gen3.recommenderagent.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.gen3.recommenderagent.domain.session.Preferences;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationWorkflow;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentActionsTest {

  @Test
  void recommendationActionDelegatesToHeavyWorkflow() {
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    SessionRequest request = new SessionRequest();
    SessionContext context = new SessionContext(new Session());
    List<Recommendation> recommendations =
        List.of(new Recommendation("book-1", 1, 1.0, "Book One"));
    org.mockito.Mockito.when(workflow.recommend(request, context)).thenReturn(recommendations);

    SessionRequest result = new RecommendationAction(workflow).execute(request, context);

    assertSame(request, result);
    assertSame(recommendations, result.getRecommendations());
    verify(workflow).recommend(request, context);
  }

  @Test
  void lightweightActionsUpdateContextWithoutARecommendationWorkflow() {
    Session session = new Session();
    session.addRequest(new SessionRequest());
    SessionContext context = new SessionContext(session);
    Preferences preferences = new Preferences();
    preferences.setInclude(List.of("science fiction"));

    SessionRequest preferenceRequest = new SessionRequest();
    preferenceRequest.setPreferences(preferences);
    new UpdatePreferencesAction().execute(preferenceRequest, context);
    assertSame(preferences, session.getPreferences());
    assertTrue(preferenceRequest.getRecommendations().isEmpty());

    SessionRequest clearRequest = new SessionRequest();
    new ClearHistoryAction().execute(clearRequest, context);
    assertTrue(session.getRequests().isEmpty());

    SessionRequest helpRequest = new SessionRequest();
    new HelpAction().execute(helpRequest, context);
    assertEquals(0, helpRequest.getRecommendations().size());
    assertTrue(helpRequest.getResponseMessage().contains("recommend audiobooks"));
  }
}
