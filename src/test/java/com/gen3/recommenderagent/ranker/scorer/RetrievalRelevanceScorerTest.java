package com.gen3.recommenderagent.ranker.scorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gen3.catalogue.model.AudiobookCandidate;
import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.recommenderagent.ranker.RankingContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetrievalRelevanceScorerTest {

  private final RetrievalRelevanceScorer scorer = new RetrievalRelevanceScorer();

  @Test
  void minMaxNormalizesBackendScoresIntoZeroToOne() {
    RankingContext context = new RankingContext(null, Set.of(), 2.0, 6.0);

    assertEquals(0.0, scorer.score(candidate("low", 2.0), context).value());
    assertEquals(0.5, scorer.score(candidate("middle", 4.0), context).value());
    assertEquals(1.0, scorer.score(candidate("high", 6.0), context).value());
  }

  @Test
  void reportsUnavailableWhenBackendDidNotReturnAScore() {
    RankingContext context = new RankingContext(null, Set.of(), null, null);

    assertFalse(scorer.score(candidate("book", null), context).available());
  }

  private AudiobookCandidate candidate(String id, Double score) {
    return new AudiobookCandidate(
        new AudiobookRecord(id, "catalogue", "Title " + id, List.of(), null), score);
  }
}
