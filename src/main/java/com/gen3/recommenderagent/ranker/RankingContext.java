package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.util.Set;

/** Optional evidence and exclusions used while ranking one candidate set. */
public record RankingContext(
    SemanticQueryVectors queryVectors,
    UserPreferenceEmbedding userPreferences,
    Set<String> excludedBookIds) {

  public RankingContext {
    queryVectors = queryVectors == null ? SemanticQueryVectors.empty() : queryVectors;
    excludedBookIds = excludedBookIds == null ? Set.of() : Set.copyOf(excludedBookIds);
  }

  public static RankingContext empty() {
    return new RankingContext(SemanticQueryVectors.empty(), null, Set.of());
  }

  public boolean hasPersonalization() {
    return !queryVectors.isEmpty() || userPreferences != null;
  }
}
