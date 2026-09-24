package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.engine.AudiobookRecommendationWorkflow;
import org.springframework.stereotype.Component;

@Component
public class BookDetailsHandler extends RetrievalIntentHandler {

  /** Uses structured fields when a detail request contains catalogue filters. */
  public BookDetailsHandler(AudiobookRecommendationWorkflow workflow) {
    super(workflow);
  }

  @Override
  public Intent supportedIntent() {
    return Intent.BOOK_DETAILS;
  }

}
