package com.gen3.recommenderagent.ranker.scorer;

import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;

/** Calculates one bounded scoring signal for a candidate. */
public interface CandidateScorer {

  /** Returns a bounded scoring signal or explicitly unavailable evidence for the candidate. */
  ScoreResult score(AudiobookCandidate candidate, RankingContext context);
}
