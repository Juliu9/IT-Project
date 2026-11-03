package com.gen3.recommenderagent.ranker.scorer;

import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;

/** Calculates one bounded scoring signal for a candidate. */
public interface CandidateScorer {

  ScoreResult score(AudiobookCandidate candidate, RankingContext context);
}
