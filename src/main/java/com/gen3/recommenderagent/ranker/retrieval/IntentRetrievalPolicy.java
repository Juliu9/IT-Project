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
    return switch (intent) {
      case SIMILAR_TO_BOOK -> RetrievalMode.SEMANTIC;
      case SIMILAR_TO_NARRATOR, FILTER_BY_NARRATOR, FILTER_BY_LENGTH, BOOK_DETAILS ->
          RetrievalMode.FILTER_ONLY;
      case NEW_RECOMMENDATION, REFINE_RECOMMENDATION, SIMILAR_TO_AUTHOR ->
          RetrievalMode.HYBRID;
      case HELP,
          CLEAR_HISTORY,
          UPDATE_PREFERENCES,
          LIKE_RECOMMENDATION,
          ALREADY_READ,
          REJECT_RECOMMENDATIONS -> RetrievalMode.NONE;
      default -> RetrievalMode.SEMANTIC;
    };
  }
}
