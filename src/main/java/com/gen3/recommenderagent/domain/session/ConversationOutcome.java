package com.gen3.recommenderagent.domain.session;

/** Resolved conversation result, independent of response wording and stored session data. */
public enum ConversationOutcome {
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
