package com.gen3.recommenderagent.ranker.strategy;

/** A scorer and its nonnegative contribution weight in a composite. */
public record WeightedScorer(CandidateScorer scorer, double weight) {

  public WeightedScorer {
    if (scorer == null) {
      throw new IllegalArgumentException("Scorer must not be null");
    }
    if (!Double.isFinite(weight) || weight <= 0.0) {
      throw new IllegalArgumentException("Scorer weight must be finite and greater than zero");
    }
  }
}
