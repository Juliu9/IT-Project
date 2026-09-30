package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.ranker.strategy.HybridRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.RelevanceRankingStrategy;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/*
   Selects and delegates to a ranking strategy based on the request.
*/
@Service
public class RankingService implements Ranker {

  private final RelevanceRankingStrategy relevanceRankingStrategy;
  private final HybridRankingStrategy hybridRankingStrategy;

  public RankingService(
      RelevanceRankingStrategy relevanceRankingStrategy,
      HybridRankingStrategy hybridRankingStrategy) {
    this.relevanceRankingStrategy = relevanceRankingStrategy;
    this.hybridRankingStrategy = hybridRankingStrategy;
  }

  /** Baseline ranking used until the ML ranking model is introduced. */
  @Override
  public List<Recommendation> rank(List<AudiobookCandidate> candidates, int requestedLimit) {
    return relevanceRankingStrategy.rank(candidates, requestedLimit);
  }

  /** Uses semantic-query vectors for polarity-aware reranking when available. */
  @Override
  public List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, SemanticQueryVectors vectors) {
    return rank(candidates, requestedLimit, new RankingContext(vectors, null, Set.of()));
  }

  /** Applies exclusions before selecting relevance-only or personalized hybrid ranking. */
  @Override
  public List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, RankingContext context) {
    RankingContext effective = context == null ? RankingContext.empty() : context;
    List<AudiobookCandidate> available = exclude(candidates, effective.excludedBookIds());
    return effective.hasPersonalization()
        ? hybridRankingStrategy.rank(available, requestedLimit, effective)
        : relevanceRankingStrategy.rank(available, requestedLimit);
  }

  private List<AudiobookCandidate> exclude(
      List<AudiobookCandidate> candidates, Set<String> excludedBookIds) {
    if (candidates == null || candidates.isEmpty() || excludedBookIds.isEmpty()) {
      return candidates;
    }
    return candidates.stream()
        .filter(
            candidate ->
                candidate == null
                    || candidate.audiobook() == null
                    || !excludedBookIds.contains(candidate.audiobook().id()))
        .toList();
  }
}
