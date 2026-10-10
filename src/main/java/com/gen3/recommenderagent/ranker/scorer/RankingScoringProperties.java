package com.gen3.recommenderagent.ranker.scorer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe configuration for all weights that contribute to candidate ranking. */
@ConfigurationProperties(prefix = "ranking.scoring")
public record RankingScoringProperties(
    double retrievalRelevanceWeight,
    double semanticWeight,
    double userPreferenceWeight,
    UserPreferenceWeights userPreference) {

  public RankingScoringProperties {
    validateWeight("retrieval-relevance-weight", retrievalRelevanceWeight);
    validateWeight("semantic-weight", semanticWeight);
    validateWeight("user-preference-weight", userPreferenceWeight);
    if (retrievalRelevanceWeight + semanticWeight + userPreferenceWeight <= 0.0) {
      throw new IllegalArgumentException(
          "At least one candidate scorer must have a positive weight");
    }
    if (userPreference == null) {
      throw new IllegalArgumentException("User-preference scorer weights must be configured");
    }
  }

  private static void validateWeight(String name, double weight) {
    if (!Double.isFinite(weight) || weight < 0.0) {
      throw new IllegalArgumentException(name + " must be finite and nonnegative");
    }
  }

  /** Relative contributions of the two available user-preference vectors. */
  public record UserPreferenceWeights(double favouritesWeight, double historyWeight) {

    public UserPreferenceWeights {
      validateWeight("user-preference.favourites-weight", favouritesWeight);
      validateWeight("user-preference.history-weight", historyWeight);
      if (favouritesWeight + historyWeight <= 0.0) {
        throw new IllegalArgumentException(
            "At least one user-preference signal must have a positive weight");
      }
    }
  }
}
