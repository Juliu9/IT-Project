package com.gen3.recommenderagent.ranker;

import com.gen3.catalogue.model.AudiobookCandidate;
import com.gen3.recommenderagent.domain.session.Recommendation;
import java.util.List;

public interface Ranker {

  List<Recommendation> rank(List<AudiobookCandidate> candidates, int requestedLimit);

  List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, RankingContext context);
}
