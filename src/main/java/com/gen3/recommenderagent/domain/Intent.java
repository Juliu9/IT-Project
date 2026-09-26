package com.gen3.recommenderagent.domain;

import com.gen3.recommenderagent.ranker.retrieval.RetrievalMode;

public enum Intent {
  // ==========================================
  // Core Recommendation Requests
  // ==========================================
  NEW_RECOMMENDATION(RetrievalMode.HYBRID), // "Recommend me a sci-fi book"
  SIMILAR_TO_BOOK(RetrievalMode.SEMANTIC), // "Find me something like Project Hail Mary"
  SIMILAR_TO_AUTHOR(RetrievalMode.HYBRID), // "I want books by Brandon Sanderson"
  SIMILAR_TO_NARRATOR(RetrievalMode.FILTER_ONLY), // "Show me audiobooks read by Ray Porter"
  // ==========================================
  // Refinement & Filtering
  // ==========================================
  REFINE_RECOMMENDATION(RetrievalMode.HYBRID), // "Make it darker/scarier"
  FILTER_BY_LENGTH(RetrievalMode.FILTER_ONLY), // "Something under 10 hours"
  FILTER_BY_NARRATOR(RetrievalMode.FILTER_ONLY), // "Must be a full cast recording"
  // ==========================================
  // Pagination & Quantity
  // ==========================================
  MORE_RESULTS(RetrievalMode.SEMANTIC), // "Show me others", "Next page"
  CHANGE_COUNT(RetrievalMode.NONE), // "Show me 10 instead of 3"
  // ==========================================
  // User Feedback
  // ==========================================
  REJECT_RECOMMENDATIONS(RetrievalMode.NONE), // "I don't like any of these"
  ALREADY_READ(RetrievalMode.NONE), // "I've already listened to that one"
  LIKE_RECOMMENDATION(RetrievalMode.NONE), // "This looks good, save it"
  // ==========================================
  // Information & Actions
  // ==========================================
  BOOK_DETAILS(RetrievalMode.FILTER_ONLY), // "What is the second one about?"
  // ==========================================
  // Profile & Session Management
  // ==========================================
  UPDATE_PREFERENCES(RetrievalMode.NONE), // "Remember that I hate romance novels"
  CLEAR_HISTORY(RetrievalMode.NONE), // "Let's start over completely"
  // ==========================================
  // Conversational & System
  // ==========================================
  HELP(RetrievalMode.NONE), // "What can you do?", "How does this work?"
  UNKNOWN(RetrievalMode.NONE); // Fallback for when the parser cannot determine the intent

  private final RetrievalMode retrievalMode;

  Intent(RetrievalMode retrievalMode) {
    this.retrievalMode = retrievalMode;
  }

  public RetrievalMode getRetrievalMode() {
    return retrievalMode;
  }
}
