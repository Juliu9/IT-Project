package com.gen3.recommenderagent.domain.conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * Literal ranks, relative positions or titles selected from one displayed page. Empty includes mean
 * all.
 */
public record ExplanationSelection(List<String> include, List<String> exclude) {
  /** Copies and deduplicates inclusion and exclusion selectors, treating missing lists as empty. */
  public ExplanationSelection {
    include = include == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(include));
    exclude = exclude == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(exclude));
  }

  /** Replaces an unresolved selector while preserving the other inclusion and exclusion choices. */
  public ExplanationSelection replace(String target, String rank) {
    return new ExplanationSelection(replace(include, target, rank), replace(exclude, target, rank));
  }

  /** Replaces an unresolved selector while preserving the other inclusion and exclusion choices. */
  private static List<String> replace(List<String> values, String target, String rank) {
    var result = new ArrayList<>(values);
    int position = result.indexOf(target);
    if (position >= 0) {
      result.set(position, rank);
    }
    return result;
  }
}
