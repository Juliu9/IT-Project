package com.gen3.recommenderagent.ranker.retrieval;

import com.gen3.recommenderagent.domain.Intent;
import org.springframework.stereotype.Component;

/** Keeps intent-to-retrieval decisions outside handlers and database adapters. */
@Component
public class IntentRetrievalPolicy {

  /** Returns the retrieval mode that best matches the requested application action. */
  public RetrievalMode modeFor(Intent intent) {
    if (intent == null) {
      return RetrievalMode.SEMANTIC;
    }
    return intent.getRetrievalMode();
  }
}
