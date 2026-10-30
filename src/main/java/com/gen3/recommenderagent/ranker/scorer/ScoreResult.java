package com.gen3.recommenderagent.ranker.scorer;

/** A score in [-1, 1], or an explicit indication that the scorer had no usable evidence. */
public record ScoreResult(boolean available, double value, boolean readingPreferencesUsed) {

  public ScoreResult {
    if (available && (!Double.isFinite(value) || value < -1.0 || value > 1.0)) {
      throw new IllegalArgumentException("Available scores must be finite and in [-1, 1]");
    }
    if (!available && readingPreferencesUsed) {
      throw new IllegalArgumentException(
          "Unavailable scores cannot carry reading-preference evidence");
    }
    if (!available && value != 0.0) {
      throw new IllegalArgumentException("Unavailable scores must have a zero value");
    }
  }

  public static ScoreResult available(double value) {
    return available(value, false);
  }

  /** Evidence describes the contribution, independently of the combined numerical score. */
  public static ScoreResult available(double value, boolean readingPreferencesUsed) {
    return new ScoreResult(true, value, readingPreferencesUsed);
  }

  public static ScoreResult unavailable() {
    return new ScoreResult(false, 0.0, false);
  }
}
