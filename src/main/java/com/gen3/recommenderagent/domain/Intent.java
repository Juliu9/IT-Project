package com.gen3.recommenderagent.domain;

public enum Intent {

    // ==========================================
    // Core Recommendation Requests
    // ==========================================
    NEW_RECOMMENDATION,      // "Recommend me a sci-fi book"
    SIMILAR_TO_BOOK,         // "Find me something like Project Hail Mary"
    SIMILAR_TO_AUTHOR,       // "I want books by Brandon Sanderson"
    SIMILAR_TO_NARRATOR,     // "Show me audiobooks read by Ray Porter" (Audiobook specific)

    // ==========================================
    // Refinement & Filtering
    // ==========================================
    REFINE_RECOMMENDATION,   // "Make it darker/scarier" or "Only non-fiction"
    FILTER_BY_LENGTH,        // "Something under 10 hours" (Audiobook specific)
    FILTER_BY_NARRATOR,      // "Only unabridged versions" or "Must be a full cast recording"

    // ==========================================
    // Pagination & Quantity
    // ==========================================
    MORE_RESULTS,            // "Show me others", "Next page"
    CHANGE_COUNT,            // "Show me 10 instead of 3"

    // ==========================================
    // User Feedback
    // ==========================================
    REJECT_RECOMMENDATIONS,  // "I don't like any of these"
    ALREADY_READ,            // "I've already listened to that one"
    LIKE_RECOMMENDATION,     // "This looks good, save it" or "Add to my list"

    // ==========================================
    // Information & Actions
    // ==========================================
    BOOK_DETAILS,            // "What is the second one about?"

    // ==========================================
    // Profile & Session Management
    // ==========================================
    UPDATE_PREFERENCES,      // "Remember that I hate romance novels"
    CLEAR_HISTORY,           // "Let's start over completely"

    // ==========================================
    // Conversational & System
    // ==========================================
    HELP,                    // "What can you do?", "How does this work?"
    UNKNOWN                  // Fallback for when the LLM/Parser cannot determine the intent
}