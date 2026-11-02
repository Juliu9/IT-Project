package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import java.util.List;

public interface Ranker {

  List<Recommendation> rank(List<AudiobookCandidate> candidates, int requestedLimit);

  List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, RankingContext context);
}
