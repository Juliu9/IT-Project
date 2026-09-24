package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.engine.AudiobookRecommendationWorkflow;
import org.springframework.stereotype.Component;

@Component
public class RefineRecommendationHandler extends RetrievalIntentHandler {

  /** Uses hybrid retrieval for the newly supplied refinement. */
  public RefineRecommendationHandler(AudiobookRecommendationWorkflow workflow) {
    super(workflow);
  }

  @Override
  public Intent supportedIntent() {
    return Intent.REFINE_RECOMMENDATION;
  }

}
