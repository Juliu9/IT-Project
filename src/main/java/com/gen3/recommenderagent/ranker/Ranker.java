package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import java.util.List;

public interface Ranker {

  List<Recommendation> rank(List<AudiobookCandidate> candidates, int requestedLimit);

  List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, SemanticQueryVectors vectors);

  List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, RankingContext context);
}
