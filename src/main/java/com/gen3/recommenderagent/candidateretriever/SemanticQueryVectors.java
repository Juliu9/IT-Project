package com.gen3.recommenderagent.candidateretriever;

import java.util.List;

/** Positive and negative semantic-query vectors shared by retrieval and ranking. */
public record SemanticQueryVectors(List<float[]> positive, List<float[]> negative) {

  public SemanticQueryVectors {
    positive = positive == null ? List.of() : List.copyOf(positive);
    negative = negative == null ? List.of() : List.copyOf(negative);
  }

  public static SemanticQueryVectors empty() {
    return new SemanticQueryVectors(List.of(), List.of());
  }

  public boolean isEmpty() {
    return positive.isEmpty() && negative.isEmpty();
  }
}
