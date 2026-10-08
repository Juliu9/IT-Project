package com.gen3.recommenderagent.ranker.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HybridScorerTest {

  @Test
  void combinesRetrievalAndSemanticSignalsUsingItsConfiguredWeights() {
    HybridScorer scorer =
        new HybridScorer(new RetrievalRelevanceScorer(), new SemanticScorer(), 2.0, 1.0);
    AudiobookCandidate candidate =
        new AudiobookCandidate(
            new AudiobookRecord("book-1", "catalogue", "Space story", List.of(), null),
            3.0,
            new float[] {1, 0});
    RankingContext context =
        new RankingContext(
            new SemanticQueryVectors(List.of(new float[] {0, 1}), List.of()),
            null,
            Set.of(),
            1.0,
            3.0);

    assertEquals(2.0 / 3.0, scorer.score(candidate, context).value(), 1e-12);
  }
}
