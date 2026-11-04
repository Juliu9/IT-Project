package com.gen3.recommenderagent.ranker.scorer;

/** A scorer and its nonnegative contribution weight in a composite. */
public record WeightedScorer(CandidateScorer scorer, double weight) {

  /** Pairs a candidate scorer with its validated positive relative weight. */
  public WeightedScorer {
    if (scorer == null) {
      throw new IllegalArgumentException("Scorer must not be null");
    }
    if (!Double.isFinite(weight) || weight <= 0.0) {
      throw new IllegalArgumentException("Scorer weight must be finite and greater than zero");
    }
  }
}
