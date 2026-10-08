package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import org.springframework.stereotype.Component;

/** Normalizes the backend's retrieval score within the current candidate set. */
@Component
public class RetrievalRelevanceScorer implements CandidateScorer {

  @Override
  public ScoreResult score(AudiobookCandidate candidate, RankingContext context) {
    if (candidate == null
        || candidate.score() == null
        || !Double.isFinite(candidate.score())
        || context == null
        || context.minimumRetrievalScore() == null
        || context.maximumRetrievalScore() == null) {
      return ScoreResult.unavailable();
    }

    double minimum = context.minimumRetrievalScore();
    double maximum = context.maximumRetrievalScore();
    if (maximum == minimum) {
      return ScoreResult.available(1.0);
    }

    double normalized = (candidate.score() - minimum) / (maximum - minimum);
    return ScoreResult.available(Math.clamp(normalized, 0.0, 1.0));
  }
}
