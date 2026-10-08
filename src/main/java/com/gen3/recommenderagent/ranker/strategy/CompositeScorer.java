package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import java.util.List;

/** Combines available scorer results as a normalized weighted average. */
public class CompositeScorer implements CandidateScorer {

  private final List<WeightedScorer> scorers;

  public CompositeScorer(List<WeightedScorer> scorers) {
    if (scorers == null || scorers.isEmpty()) {
      throw new IllegalArgumentException("Composite scorer requires at least one scorer");
    }
    this.scorers = List.copyOf(scorers);
  }

  @Override
  public ScoreResult score(AudiobookCandidate candidate, RankingContext context) {
    double weightedTotal = 0.0;
    double availableWeight = 0.0;
    for (WeightedScorer weightedScorer : scorers) {
      ScoreResult result = weightedScorer.scorer().score(candidate, context);
      if (result == null) {
        throw new IllegalStateException("Candidate scorers must return a ScoreResult");
      }
      if (result.available()) {
        weightedTotal += weightedScorer.weight() * result.value();
        availableWeight += weightedScorer.weight();
      }
    }
    return availableWeight == 0.0
        ? ScoreResult.unavailable()
        : ScoreResult.available(clamp(weightedTotal / availableWeight));
  }

  private double clamp(double score) {
    return Math.clamp(score, -1.0, 1.0);
  }
}
