package com.gen3.recommenderagent.ranker.retrieval;

import java.util.List;

/** Structured constraints applied independently from semantic or lexical scoring. */
public record AudiobookFilters(
    List<String> authors,
    List<String> narrators,
    String language,
    Integer maximumDurationMinutes) {

  /** Returns an empty filter object so callers do not need null checks. */
  public static AudiobookFilters empty() {
    return new AudiobookFilters(List.of(), List.of(), null, null);
  }

  /** Reports whether at least one payload condition must be applied. */
  public boolean hasConditions() {
    return (authors != null && !authors.isEmpty())
        || (narrators != null && !narrators.isEmpty())
        || (language != null && !language.isBlank())
        || maximumDurationMinutes != null;
  }
}
