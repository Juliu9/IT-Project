package com.gen3.recommenderagent.retrieval.plan;

import com.gen3.catalogue.model.AudiobookFilters;

/** Complete, database-ready interpretation of one audiobook retrieval request. */
public record AudiobookRetrievalPlan(
    RetrievalMode mode, String keywordText, AudiobookFilters filters, int limit) {}
