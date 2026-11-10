package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.util.Set;

/** Optional evidence and exclusions used while ranking one candidate set. */
public record RankingContext(
    UserPreferenceEmbedding userPreferences,
    Set<String> excludedBookIds,
    Double minimumRetrievalScore,
    Double maximumRetrievalScore,
    float[] searchHistoryTaste) {

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

  // Keeps the original four-value form working now that search history is included (MAPA-30).
  public RankingContext(
      UserPreferenceEmbedding userPreferences,
      Set<String> excludedBookIds,
      Double minimumRetrievalScore,
      Double maximumRetrievalScore) {
    this(userPreferences, excludedBookIds, minimumRetrievalScore, maximumRetrievalScore, null);
  }

  /** Copies the scoring context with the observed retrieval-score range for normalization. */
  public RankingContext withRetrievalScoreRange(Double minimum, Double maximum) {
    return new RankingContext(
        userPreferences, excludedBookIds, minimum, maximum, searchHistoryTaste);
  }

  public RankingContext withSearchHistoryTaste(float[] taste) {
    return new RankingContext(
        userPreferences, excludedBookIds, minimumRetrievalScore, maximumRetrievalScore, taste);
  }

  /** Returns an instance with no available values or evidence. */
  public static RankingContext empty() {
    return new RankingContext(null, Set.of(), null, null);
  }
}
