package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.engine.AudiobookRecommendationWorkflow;
import org.springframework.stereotype.Component;

@Component
public class SimilarToAuthorHandler extends RetrievalIntentHandler {

  /** Uses hybrid retrieval for an author-related recommendation request. */
  public SimilarToAuthorHandler(AudiobookRecommendationWorkflow workflow) {
    super(workflow);
  }

  @Override
  public Intent supportedIntent() {
    return Intent.SIMILAR_TO_AUTHOR;
  }

}
