package com.gen3.recommenderagent.domain.conversation;

/** Exceptional completed result that cannot be reconstructed from the conversation turn. */
public enum ConversationOutcome {
  UNAVAILABLE,
  PREFERENCE_LIMIT_EXCEEDED,
  SEARCH_DECLINED,
  RECENCY_EXHAUSTED,
  NOTHING_TO_REPEAT,
  NO_RECOMMENDATIONS_TO_REPEAT,
  NOTHING_TO_EXPLAIN,
  EMPTY_EXPLANATION_PAGE,
  EMPTY_EXPLANATION_SELECTION
}
