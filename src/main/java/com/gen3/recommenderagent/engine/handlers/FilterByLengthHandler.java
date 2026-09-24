package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.engine.AudiobookRecommendationWorkflow;
import org.springframework.stereotype.Component;

@Component
public class FilterByLengthHandler extends RetrievalIntentHandler {

  /** Uses the parsed duration as a Qdrant payload filter. */
  public FilterByLengthHandler(AudiobookRecommendationWorkflow workflow) {
    super(workflow);
  }

  @Override
  public Intent supportedIntent() {
    return Intent.FILTER_BY_LENGTH;
  }

}
