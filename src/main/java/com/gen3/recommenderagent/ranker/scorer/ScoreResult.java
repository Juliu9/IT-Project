package com.gen3.recommenderagent.ranker.scorer;

/** A score in [-1, 1], or an explicit indication that the scorer had no usable evidence. */
public record ScoreResult(boolean available, double value) {

  public ScoreResult {
    if (available && (!Double.isFinite(value) || value < -1.0 || value > 1.0)) {
      throw new IllegalArgumentException("Available scores must be finite and in [-1, 1]");
    }
    if (!available && value != 0.0) {
      throw new IllegalArgumentException("Unavailable scores must have a zero value");
    }
  }

  public static ScoreResult available(double value) {
    return new ScoreResult(true, value);
  }

  public static ScoreResult unavailable() {
    return new ScoreResult(false, 0.0);
  }
}
