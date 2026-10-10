package com.gen3.recommenderagent.ranker.scorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticScorerTest {

  @Test
  void negativeQuerySimilarityProducesNegativeScore() {
    SemanticScorer scorer = new SemanticScorer();
    AudiobookCandidate unwanted = candidate("horror", new float[] {0, 1});
    var context =
        new RankingContext(
            new SemanticQueryVectors(List.of(), List.of(new float[] {0, 1})), null, Set.of());

    assertEquals(-1.0, scorer.score(unwanted, context).value());
  }

  @Test
  void returnsUnavailableWhenNoComparableQueryVectorsExist() {
    var result =
        new SemanticScorer()
            .score(
                candidate("book", new float[] {1, 0}),
                new RankingContext(SemanticQueryVectors.empty(), null, Set.of()));

    assertFalse(result.available());
  }

  private AudiobookCandidate candidate(String id, float[] embedding) {
    AudiobookRecord book = new AudiobookRecord(id, "catalogue", "Title " + id, List.of(), null);
    return new AudiobookCandidate(book, 0.5, embedding);
  }
}
