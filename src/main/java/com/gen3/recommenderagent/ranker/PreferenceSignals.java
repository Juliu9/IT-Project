package com.gen3.recommenderagent.ranker;

import java.util.List;

/** Positive and negative preference vectors used while reranking retrieved audiobooks. */
public record PreferenceSignals(List<float[]> positive, List<float[]> negative) {

  public PreferenceSignals {
    positive = positive == null ? List.of() : List.copyOf(positive);
    negative = negative == null ? List.of() : List.copyOf(negative);
  }

  public static PreferenceSignals empty() {
    return new PreferenceSignals(List.of(), List.of());
  }

  public boolean isEmpty() {
    return positive.isEmpty() && negative.isEmpty();
  }
}
