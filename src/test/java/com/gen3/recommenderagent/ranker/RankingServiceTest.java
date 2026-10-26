package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gen3.catalogue.model.AudiobookCandidate;
import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.ranker.scorer.CompositeScorer;
import com.gen3.recommenderagent.ranker.scorer.RetrievalRelevanceScorer;
import com.gen3.recommenderagent.ranker.scorer.ScoreResult;
import com.gen3.recommenderagent.ranker.scorer.WeightedScorer;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RankingServiceTest {

  private final RankingService rankingService =
      new RankingService(
          new CompositeScorer(List.of(new WeightedScorer(new RetrievalRelevanceScorer(), 1.0))));

  @Test
  void retainsCatalogueMetadataForLaterRelativeRefinements() {
    var record =
        new AudiobookRecord(
            "science",
            "test",
            "Science Fiction",
            List.of(),
            "Description",
            List.of(),
            "English",
            180,
            2024,
            "Summary");
    var result = rankingService.rank(List.of(new AudiobookCandidate(record, 1.0)), 1);
    assertEquals(180, result.getFirst().getDurationMinutes());
    assertEquals(2024, result.getFirst().getPublicationYear());
  }

  @Test
  void replacementScorerSuppliesEvidenceWithoutRescoringSelectedBooks() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    com.gen3.recommenderagent.ranker.scorer.CandidateScorer replacement =
        (book, context) -> {
          calls.incrementAndGet();
          return ScoreResult.available(0.5, true);
        };
    var service =
        new RankingService(
            new CompositeScorer(
                List.of(
                    new WeightedScorer(replacement, 1),
                    new WeightedScorer((book, context) -> ScoreResult.available(-0.5), 1))));
    var result = service.rank(List.of(candidate("first", 1.0), candidate("second", 0.5)), 1);
    assertEquals(2, calls.get());
    assertEquals(0.0, result.getFirst().getScore());
    assertEquals(true, result.getFirst().isReadingPreferencesUsed());
  }

  @Test
  void recordsPreferencesOnlyWhenAnActivePreferenceScorerHasPositiveEvidence() {
    var properties =
        new com.gen3.recommenderagent.ranker.scorer.RankingScoringProperties(
            1,
            1,
            0,
            new com.gen3.recommenderagent.ranker.scorer.RankingScoringProperties
                .UserPreferenceWeights(1, 0),
            new com.gen3.recommenderagent.ranker.scorer.RankingScoringProperties
                .UniversalRuleWeights(1, 0));
    var preferenceScorer =
        new com.gen3.recommenderagent.ranker.scorer.UserPreferenceScorer(properties);
    var service =
        new RankingService(
            new CompositeScorer(
                List.of(
                    new WeightedScorer(new RetrievalRelevanceScorer(), 1),
                    new WeightedScorer(preferenceScorer, 1))));
    var preference =
        new com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding(
            "user", new float[] {1, 0}, null);
    var matching =
        new AudiobookCandidate(candidate("match", 1.0).audiobook(), 1.0, new float[] {1, 0});
    var unrelated =
        new AudiobookCandidate(candidate("unrelated", 1.0).audiobook(), 1.0, new float[] {0, 1});
    var result =
        service.rank(List.of(matching, unrelated), 2, new RankingContext(preference, Set.of()));
    assertEquals(true, result.getFirst().isReadingPreferencesUsed());
    assertEquals(false, result.getLast().isReadingPreferencesUsed());
    assertEquals(false, service.rank(List.of(matching), 1).getFirst().isReadingPreferencesUsed());
    assertEquals(
        false,
        rankingService
            .rank(List.of(matching), 1, new RankingContext(preference, Set.of()))
            .getFirst()
            .isReadingPreferencesUsed());
  }

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
    RankingContext context = new RankingContext(null, Set.of("book-1"));

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

  @Test
  void ranksAnInternalBatchLargerThanTheApiPageSize() {
    var candidates =
        IntStream.rangeClosed(1, 25).mapToObj(i -> candidate("book-" + i, 100.0 - i)).toList();
    List<Recommendation> result = rankingService.rank(candidates, 20);
    assertEquals(20, result.size());
    assertEquals("book-1", result.getFirst().getBookId());
    assertEquals("book-20", result.getLast().getBookId());
  }

  private AudiobookCandidate candidate(String id, Double score) {
    return new AudiobookCandidate(
        new AudiobookRecord(id, "catalogue", "Title " + id, List.of(), null), score);
  }
}
