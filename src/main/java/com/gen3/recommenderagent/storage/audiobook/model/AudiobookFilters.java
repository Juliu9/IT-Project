package com.gen3.recommenderagent.storage.audiobook.model;

/** Structured catalogue constraints shared by retrieval policies and database adapters. */
public record AudiobookFilters(
    AudiobookFilterConditions mustInclude, AudiobookFilterConditions mustNotInclude) {

  /** Returns an empty filter object so callers do not need null checks. */
  public static AudiobookFilters empty() {
    return new AudiobookFilters(
        AudiobookFilterConditions.empty(), AudiobookFilterConditions.empty());
  }

  /** Reports whether at least one payload condition must be applied. */
  public boolean hasConditions() {
    return (mustInclude != null && mustInclude.hasConditions())
        || (mustNotInclude != null && mustNotInclude.hasConditions());
  }
}
