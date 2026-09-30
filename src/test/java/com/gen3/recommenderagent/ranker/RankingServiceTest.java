package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.ranker.strategy.HybridRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.RelevanceRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.SemanticQueryRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.UserPreferenceRankingStrategy;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class RankingServiceTest {

  private final SemanticQueryRankingStrategy semanticQueryRankingStrategy =
      new SemanticQueryRankingStrategy();
  private final RankingService rankingService =
      new RankingService(
          new RelevanceRankingStrategy(),
          new HybridRankingStrategy(
              semanticQueryRankingStrategy, new UserPreferenceRankingStrategy()));

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
    assertEquals(3.5, result.get(0).getScore());
    assertEquals("book-2", result.get(1).getBookId());
    assertEquals(2, result.get(1).getRank());
    assertNull(result.get(1).getScore());
  }

  @Test
  void excludesShownBooksBeforeRanking() {
    RankingContext context =
        new RankingContext(
            com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors.empty(),
            null,
            java.util.Set.of("book-1"));

    List<Recommendation> result =
        rankingService.rank(
            List.of(candidate("book-1", 4.0), candidate("book-2", 3.0)), 2, context);

    assertEquals(1, result.size());
    assertEquals("book-2", result.getFirst().getBookId());
  }

  private AudiobookCandidate candidate(String id, Double score) {
    return new AudiobookCandidate(
        new AudiobookRecord(id, "catalogue", "Title " + id, List.of(), null), score);
  }
}
