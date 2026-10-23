package com.gen3.recommenderagent.domain;

/** User-level operations. Candidate retrieval is derived from request content, not intent. */
public enum Intent {
  RECOMMENDATION,
  REFINE,
  MORE_RESULTS,
  REPEAT,
  REPEAT_RECOMMENDATIONS,
  EXPLAIN_RECOMMENDATION,
  CHANGE_COUNT,
  UPDATE_PREFERENCES,
  CLEAR_HISTORY,
  HELP,
  UNKNOWN;
}
