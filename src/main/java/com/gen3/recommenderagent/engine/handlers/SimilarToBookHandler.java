package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.engine.AudiobookRecommendationWorkflow;
import org.springframework.stereotype.Component;

@Component
public class SimilarToBookHandler extends RetrievalIntentHandler {

  /** Uses semantic retrieval for the referenced book request. */
  public SimilarToBookHandler(AudiobookRecommendationWorkflow workflow) {
    super(workflow);
  }

  @Override
  public Intent supportedIntent() {
    return Intent.SIMILAR_TO_BOOK;
  }

}
