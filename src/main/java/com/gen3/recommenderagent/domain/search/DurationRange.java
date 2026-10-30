package com.gen3.recommenderagent.domain.search;

/** Inclusive whole-minute duration bounds for an audiobook search. */
public record DurationRange(Integer minimum, Integer maximum) {
  public DurationRange {
    if ((minimum != null && minimum < 1)
        || (maximum != null && maximum < 1)
        || (minimum != null && maximum != null && minimum > maximum)) {
      throw new IllegalArgumentException("Duration bounds must be positive and ordered");
    }
  }
}
