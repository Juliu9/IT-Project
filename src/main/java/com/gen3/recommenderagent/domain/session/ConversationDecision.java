package com.gen3.recommenderagent.domain.session;

/** Policy decision about the next conversational action, independent of response wording. */
public enum ConversationDecision {
  UNAVAILABLE,
  SEARCH_CLARIFICATION,
  PREFERENCE_CLARIFICATION,
  COUNT_CLARIFICATION,
  COUNT_CHANGED,
  PREFERENCES_UPDATED,
  HISTORY_CLEARED,
  HELP,
  UNKNOWN,
  NO_MORE_RESULTS,
  NOTHING_TO_REPEAT,
  NO_MATCHES,
  RECOMMENDATIONS
}
