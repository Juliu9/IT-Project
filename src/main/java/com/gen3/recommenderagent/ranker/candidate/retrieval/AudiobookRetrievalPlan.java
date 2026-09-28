package com.gen3.recommenderagent.ranker.candidate.retrieval;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;

/** Complete, database-ready interpretation of one audiobook retrieval request. */
public record AudiobookRetrievalPlan(
    RetrievalMode mode, String keywordText, AudiobookFilters filters, int limit) {}
