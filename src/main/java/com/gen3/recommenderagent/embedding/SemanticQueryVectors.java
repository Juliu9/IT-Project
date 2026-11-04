package com.gen3.recommenderagent.embedding;

import java.util.List;

/** Positive and negative semantic-query vectors shared by retrieval and ranking. */
public record SemanticQueryVectors(List<float[]> positive, List<float[]> negative) {

  /** Retains separate positive and negative collections of semantic vector evidence. */
  public SemanticQueryVectors {
    positive = positive == null ? List.of() : List.copyOf(positive);
    negative = negative == null ? List.of() : List.copyOf(negative);
  }

  /** Returns an instance with no available values or evidence. */
  public static SemanticQueryVectors empty() {
    return new SemanticQueryVectors(List.of(), List.of());
  }

  public boolean isEmpty() {
    return positive.isEmpty() && negative.isEmpty();
  }
}
