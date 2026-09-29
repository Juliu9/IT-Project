package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.ranker.strategy.HybridRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.RelevanceRankingStrategy;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import java.util.List;
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
    return vectors.isEmpty()
        ? relevanceRankingStrategy.rank(candidates, requestedLimit)
        : hybridRankingStrategy.rank(candidates, requestedLimit, vectors);
  }
}
