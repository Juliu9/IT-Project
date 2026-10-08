package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;

/** Calculates one bounded scoring signal for a candidate. */
public interface CandidateScorer {

  ScoreResult score(AudiobookCandidate candidate, RankingContext context);
}
