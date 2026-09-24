package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.engine.AudiobookRecommendationWorkflow;
import org.springframework.stereotype.Component;

@Component
public class SimilarToNarratorHandler extends RetrievalIntentHandler {

  /** Uses exact narrator payload filtering for this request. */
  public SimilarToNarratorHandler(AudiobookRecommendationWorkflow workflow) {
    super(workflow);
  }

  @Override
  public Intent supportedIntent() {
    return Intent.SIMILAR_TO_NARRATOR;
  }

}
