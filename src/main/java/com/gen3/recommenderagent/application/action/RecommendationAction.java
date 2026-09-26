package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationWorkflow;
import org.springframework.stereotype.Component;

/** Enters the retrieval and ranking pipeline for recommendation-producing requests. */
@Component
public class RecommendationAction implements IntentAction {

  private final RecommendationWorkflow workflow;

  public RecommendationAction(RecommendationWorkflow workflow) {
    this.workflow = workflow;
  }

  @Override
  public SessionRequest execute(SessionRequest request, SessionContext context) {
    request.setRecommendations(workflow.recommend(request, context));
    return request;
  }
}
