package com.gen3.recommenderagent.ranker.retrieval;

/** Complete, database-ready interpretation of one audiobook retrieval request. */
public record AudiobookRetrievalPlan(
    RetrievalMode mode,
    String semanticText,
    String keywordText,
    AudiobookFilters filters,
    int limit) {}
