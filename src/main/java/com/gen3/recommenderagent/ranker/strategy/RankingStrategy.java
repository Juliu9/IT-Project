package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import java.util.List;

public interface RankingStrategy {

  List<Recommendation> rank(List<AudiobookCandidate> candidates, int requestedLimit);
}
