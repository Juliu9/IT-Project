package com.gen3.recommenderagent.ranker.scorer;

import com.gen3.catalogue.model.AudiobookCandidate;
import com.gen3.recommenderagent.ranker.RankingContext;

/** Calculates one bounded scoring signal for a candidate. */
public interface CandidateScorer {

  ScoreResult score(AudiobookCandidate candidate, RankingContext context);
}
