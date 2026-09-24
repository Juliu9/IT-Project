package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.AudiobookRecommendationWorkflow;
import java.util.List;

/** Base handler for intents that retrieve and rank audiobook candidates in the same way. */
public abstract class RetrievalIntentHandler implements IntentHandler {

  private final AudiobookRecommendationWorkflow workflow;

  /** Stores the shared workflow while each subclass supplies only its supported intent. */
  protected RetrievalIntentHandler(AudiobookRecommendationWorkflow workflow) {
    this.workflow = workflow;
  }

  /** Delegates retrieval mode selection to the candidate retriever through the workflow. */
  @Override
  public List<Recommendation> handle(SessionRequest request, Session session) {
    return workflow.recommend(request);
  }
}
