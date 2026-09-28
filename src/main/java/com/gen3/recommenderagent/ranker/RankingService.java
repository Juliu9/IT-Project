package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.ranker.strategy.HybridRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.RelevanceRankingStrategy;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import java.util.List;
import org.springframework.stereotype.Service;

/*
   Selects and delegates to a ranking strategy based on the request.
*/
@Service
public class RankingService implements Ranker {

  private final RelevanceRankingStrategy relevanceRankingStrategy;
  private final HybridRankingStrategy hybridRankingStrategy;
  private final PreferenceVectorService preferenceVectorService;

  public RankingService(
      RelevanceRankingStrategy relevanceRankingStrategy,
      HybridRankingStrategy hybridRankingStrategy,
      PreferenceVectorService preferenceVectorService) {
    this.relevanceRankingStrategy = relevanceRankingStrategy;
    this.hybridRankingStrategy = hybridRankingStrategy;
    this.preferenceVectorService = preferenceVectorService;
  }

  /** Baseline ranking used until the ML ranking model is introduced. */
  @Override
  public List<Recommendation> rank(List<AudiobookCandidate> candidates, int requestedLimit) {
    return relevanceRankingStrategy.rank(candidates, requestedLimit);
  }

  /** Builds separate preference vectors and only reranks when at least one signal exists. */
  @Override
  public List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, SessionRequest request) {
    PreferenceSignals signals = preferenceVectorService.create(request);
    return signals.isEmpty()
        ? relevanceRankingStrategy.rank(candidates, requestedLimit)
        : hybridRankingStrategy.rank(candidates, requestedLimit, signals);
  }
}
