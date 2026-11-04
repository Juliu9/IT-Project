package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.util.Set;

/** Optional evidence and exclusions used while ranking one candidate set. */
public record RankingContext(
    UserPreferenceEmbedding userPreferences,
    Set<String> excludedBookIds,
    Double minimumRetrievalScore,
    Double maximumRetrievalScore) {

  /**
   * Captures stored taste evidence, excluded book IDs, and optional retrieval-score normalization
   * bounds.
   */
  public RankingContext {
    excludedBookIds = excludedBookIds == null ? Set.of() : Set.copyOf(excludedBookIds);
  }

  /**
   * Captures stored taste evidence, excluded book IDs, and optional retrieval-score normalization
   * bounds.
   */
  public RankingContext(UserPreferenceEmbedding userPreferences, Set<String> excludedBookIds) {
    this(userPreferences, excludedBookIds, null, null);
  }

  /** Copies the scoring context with the observed retrieval-score range for normalization. */
  public RankingContext withRetrievalScoreRange(Double minimum, Double maximum) {
    return new RankingContext(userPreferences, excludedBookIds, minimum, maximum);
  }

  /** Returns an instance with no available values or evidence. */
  public static RankingContext empty() {
    return new RankingContext(null, Set.of(), null, null);
  }
}
