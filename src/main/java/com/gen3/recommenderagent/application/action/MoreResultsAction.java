package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationWorkflow;
import org.springframework.stereotype.Component;

/** Reuses the latest search criteria and returns unseen recommendations. */
@Component
public class MoreResultsAction implements IntentAction {

  private final RecommendationWorkflow workflow;
  private final RequestContextMerger requestContextMerger;

  public MoreResultsAction(
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
