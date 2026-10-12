package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.util.Set;

/** Optional evidence and exclusions used while ranking one candidate set. */
public record RankingContext(
    UserPreferenceEmbedding userPreferences,
    Set<String> excludedBookIds,
    Double minimumRetrievalScore,
    Double maximumRetrievalScore) {

  public RankingContext {
    excludedBookIds = excludedBookIds == null ? Set.of() : Set.copyOf(excludedBookIds);
  }

  public RankingContext(UserPreferenceEmbedding userPreferences, Set<String> excludedBookIds) {
    this(userPreferences, excludedBookIds, null, null);
  }

  public RankingContext withRetrievalScoreRange(Double minimum, Double maximum) {
    return new RankingContext(userPreferences, excludedBookIds, minimum, maximum);
  }

  public static RankingContext empty() {
    return new RankingContext(null, Set.of(), null, null);
  }
}
