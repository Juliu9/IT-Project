package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Combines keyword and semantic scoring as one selectable hybrid signal. */
@Component
public class HybridScorer implements CandidateScorer {

  private final CompositeScorer compositeScorer;

  public HybridScorer(
      RetrievalRelevanceScorer retrievalRelevanceScorer,
      SemanticScorer semanticScorer,
      @Value("${ranking.scoring.hybrid.retrieval-relevance-weight:1.0}")
          double retrievalRelevanceWeight,
      @Value("${ranking.scoring.hybrid.semantic-weight:1.0}") double semanticWeight) {
    List<WeightedScorer> scorers = new ArrayList<>();
    add(scorers, retrievalRelevanceScorer, retrievalRelevanceWeight);
    add(scorers, semanticScorer, semanticWeight);
    this.compositeScorer = new CompositeScorer(scorers);
  }

  @Override
  public ScoreResult score(AudiobookCandidate candidate, RankingContext context) {
    return compositeScorer.score(candidate, context);
  }

  private void add(List<WeightedScorer> scorers, CandidateScorer scorer, double weight) {
    if (!Double.isFinite(weight) || weight < 0.0) {
      throw new IllegalArgumentException("Hybrid scorer weights must be finite and nonnegative");
    }
    if (weight > 0.0) {
      scorers.add(new WeightedScorer(scorer, weight));
    }
  }
}
