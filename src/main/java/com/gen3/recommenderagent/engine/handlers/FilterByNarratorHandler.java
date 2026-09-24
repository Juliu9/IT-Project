package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.engine.AudiobookRecommendationWorkflow;
import org.springframework.stereotype.Component;

@Component
public class FilterByNarratorHandler extends RetrievalIntentHandler {

  /** Uses parsed narrator names as exact Qdrant payload filters. */
  public FilterByNarratorHandler(AudiobookRecommendationWorkflow workflow) {
    super(workflow);
  }

  @Override
  public Intent supportedIntent() {
    return Intent.FILTER_BY_NARRATOR;
  }

}
