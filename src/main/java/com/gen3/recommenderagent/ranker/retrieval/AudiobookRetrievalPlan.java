package com.gen3.recommenderagent.ranker.retrieval;

import com.gen3.recommenderagent.storage.audiobook.AudiobookFilters;

/** Complete, database-ready interpretation of one audiobook retrieval request. */
public record AudiobookRetrievalPlan(
    RetrievalMode mode,
    String keywordText,
    AudiobookFilters filters,
    int limit) {}
