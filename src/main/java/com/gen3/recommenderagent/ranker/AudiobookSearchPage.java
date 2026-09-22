package com.gen3.recommenderagent.ranker;

import java.util.List;

/** A database-independent page of audiobook search results. */
public record AudiobookSearchPage(long totalItems, List<AudiobookRecord> records) {}
