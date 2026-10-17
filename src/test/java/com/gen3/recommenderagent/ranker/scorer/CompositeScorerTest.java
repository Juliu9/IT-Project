package com.gen3.recommenderagent.ranker.scorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompositeScorerTest {

  private final AudiobookCandidate candidate =
      new AudiobookCandidate(
          new AudiobookRecord("book-1", "catalogue", "Title", List.of(), null), null);

  @Test
  void combinesScoresByWeightAndRenormalizesOverAvailableScorers() {
    CandidateScorer positive = (book, context) -> ScoreResult.available(0.8);
    CandidateScorer unavailable = (book, context) -> ScoreResult.unavailable();
    CompositeScorer composite =
        new CompositeScorer(
            List.of(new WeightedScorer(positive, 3.0), new WeightedScorer(unavailable, 7.0)));

    ScoreResult result = composite.score(candidate, RankingContext.empty());

    assertEquals(0.8, result.value(), 1e-12);
  }

  @Test
  void preservesSignedScoringWhenCombiningPositiveAndNegativeEvidence() {
    CompositeScorer composite =
        new CompositeScorer(
            List.of(
                new WeightedScorer((book, context) -> ScoreResult.available(1.0), 1.0),
                new WeightedScorer((book, context) -> ScoreResult.available(-1.0), 1.0)));

    assertEquals(0.0, composite.score(candidate, RankingContext.empty()).value());
  }

  @Test
  void reportsUnavailableWhenNoChildHasEvidence() {
    CompositeScorer composite =
        new CompositeScorer(
            List.of(new WeightedScorer((book, context) -> ScoreResult.unavailable(), 1.0)));

    assertFalse(composite.score(candidate, RankingContext.empty()).available());
  }
}
