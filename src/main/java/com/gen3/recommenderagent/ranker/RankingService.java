package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.ranker.strategy.HybridRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.PreferenceRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.RelevanceRankingStrategy;
import java.util.List;
import org.apache.solr.common.SolrDocument;
import org.springframework.stereotype.Service;

/*
   Selects and delegates to a ranking strategy based on the request.
*/
@Service
public class RankingService implements Ranker {

  private final RelevanceRankingStrategy relevanceRankingStrategy;
  private final PreferenceRankingStrategy preferenceRankingStrategy;
  private final HybridRankingStrategy hybridRankingStrategy;

  public RankingService(
      RelevanceRankingStrategy relevanceRankingStrategy,
      PreferenceRankingStrategy preferenceRankingStrategy,
      HybridRankingStrategy hybridRankingStrategy) {
    this.relevanceRankingStrategy = relevanceRankingStrategy;
    this.preferenceRankingStrategy = preferenceRankingStrategy;
    this.hybridRankingStrategy = hybridRankingStrategy;
  }

  /** Baseline ranking used until the ML ranking model is introduced. */
  @Override
  public List<Recommendation> rank(List<SolrDocument> candidates, int requestedLimit) {
    return relevanceRankingStrategy.rank(candidates, requestedLimit);
  }

  @Override
  public List<Recommendation> rank(
      List<SolrDocument> candidates, int requestedLimit, boolean personalised) {
    return personalised
        ? hybridRankingStrategy.rank(candidates, requestedLimit)
        : relevanceRankingStrategy.rank(candidates, requestedLimit);
  }
}
