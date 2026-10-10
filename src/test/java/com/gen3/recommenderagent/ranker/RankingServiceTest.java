package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.ranker.scorer.CompositeScorer;
import com.gen3.recommenderagent.ranker.scorer.RetrievalRelevanceScorer;
import com.gen3.recommenderagent.ranker.scorer.ScoreResult;
import com.gen3.recommenderagent.ranker.scorer.WeightedScorer;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RankingServiceTest {

  private final RankingService rankingService =
      new RankingService(
          new CompositeScorer(List.of(new WeightedScorer(new RetrievalRelevanceScorer(), 1.0))));

  @Test
  void shouldPreserveCandidateOrderRemoveDuplicatesAndLimitResults() {
    AudiobookCandidate first = candidate("book-1", 3.5);
    AudiobookCandidate duplicate = candidate("book-1", 3.0);
    AudiobookCandidate missingId = candidate(null, 4.0);
    AudiobookCandidate second = candidate("book-2", null);
    AudiobookCandidate third = candidate("book-3", 1.5);

    List<Recommendation> result =
        rankingService.rank(List.of(first, duplicate, missingId, second, third), 2);

    assertEquals(2, result.size());
    assertEquals("book-1", result.get(0).getBookId());
    assertEquals(1, result.get(0).getRank());
    assertEquals(1.0, result.get(0).getScore());
    assertEquals("book-2", result.get(1).getBookId());
    assertEquals(2, result.get(1).getRank());
    assertEquals(0.0, result.get(1).getScore());
  }

  @Test
  void excludesShownBooksBeforeRanking() {
    RankingContext context =
        new RankingContext(SemanticQueryVectors.empty(), null, Set.of("book-1"));

    List<Recommendation> result =
        rankingService.rank(
            List.of(candidate("book-1", 4.0), candidate("book-2", 3.0)), 2, context);

    assertEquals(1, result.size());
    assertEquals("book-2", result.getFirst().getBookId());
  }

  @Test
  void normalizesActiveScoresAndRanksByCompositeScore() {
    RankingService service =
        new RankingService(
            new CompositeScorer(
                List.of(
                    new WeightedScorer(
                        (candidate, context) ->
                            ScoreResult.available(
                                candidate.audiobook().id().equals("book-2") ? 1 : 0),
                        1.0))));

    List<Recommendation> result =
        service.rank(
            List.of(candidate("book-1", 9.0), candidate("book-2", 1.0)), 2, RankingContext.empty());

    assertEquals("book-2", result.getFirst().getBookId());
    assertEquals(1.0, result.getFirst().getScore());
    assertEquals("book-1", result.getLast().getBookId());
    assertEquals(0.0, result.getLast().getScore());
  }

  @Test
  void searchScoresAreNormalizedWithinCurrentCandidateSet() {
    RankingService service =
        new RankingService(
            new CompositeScorer(List.of(new WeightedScorer(new RetrievalRelevanceScorer(), 1.0))));

    List<Recommendation> result =
        service.rank(List.of(candidate("low", 2.0), candidate("high", 6.0)), 2);

    assertEquals("high", result.getFirst().getBookId());
    assertEquals(1.0, result.getFirst().getScore());
    assertEquals("low", result.getLast().getBookId());
    assertEquals(0.0, result.getLast().getScore());
  }

  private AudiobookCandidate candidate(String id, Double score) {
    return new AudiobookCandidate(
        new AudiobookRecord(id, "catalogue", "Title " + id, List.of(), null), score);
  }
}
