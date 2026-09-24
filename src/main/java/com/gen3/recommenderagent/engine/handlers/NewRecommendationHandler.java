package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.engine.AudiobookRecommendationWorkflow;
import org.springframework.stereotype.Component;

@Component
public class NewRecommendationHandler extends RetrievalIntentHandler {

  /** Uses the shared retrieval and ranking workflow for a new recommendation request. */
  public NewRecommendationHandler(AudiobookRecommendationWorkflow workflow) {
    super(workflow);
  }

  @Override
  public Intent supportedIntent() {
    return Intent.NEW_RECOMMENDATION;
  }

}
