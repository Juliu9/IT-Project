package com.gen3.recommenderagent.candidateretriever.retrievalplan;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;

/** Complete, database-ready interpretation of one audiobook retrieval request. */
public record AudiobookRetrievalPlan(
    RetrievalMode mode, String keywordText, AudiobookFilters filters, int limit) {}
