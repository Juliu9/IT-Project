package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationWorkflow;
import org.springframework.stereotype.Component;

/** Reuses the latest search and session preferences for refine and more-results requests. */
@Component
public class ContinuationRecommendationAction implements IntentAction {

  private final RecommendationWorkflow workflow;
  private final RequestContextMerger requestContextMerger;

  public ContinuationRecommendationAction(
      RecommendationWorkflow workflow, RequestContextMerger requestContextMerger) {
    this.workflow = workflow;
    this.requestContextMerger = requestContextMerger;
  }

  @Override
  public SessionRequest execute(SessionRequest request, Session session) {
    requestContextMerger.applyPreviousSearch(request, session);
    requestContextMerger.applyPreferences(request, session);
    requestContextMerger.applyBookCount(request, session);
    request.setRecommendations(workflow.recommend(request, session));
    return request;
  }
}
