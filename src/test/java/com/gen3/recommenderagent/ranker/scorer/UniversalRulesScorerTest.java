package com.gen3.recommenderagent.ranker.scorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gen3.catalogue.model.AudiobookCandidate;
import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.recommenderagent.ranker.RankingContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class UniversalRulesScorerTest {

  private static final int CURRENT_YEAR = 2026;
  private final UniversalRulesScorer scorer =
      new UniversalRulesScorer(properties(0.7, 0.3), () -> CURRENT_YEAR);

  @Test
  void scoresEachPublicationAgeBand() {
    assertEquals(1.0, score(book(CURRENT_YEAR - 5, null)), 0.000_001);
    assertEquals(0.8, score(book(CURRENT_YEAR - 15, null)), 0.000_001);
    assertEquals(0.6, score(book(CURRENT_YEAR - 30, null)), 0.000_001);
    assertEquals(0.45, score(book(CURRENT_YEAR - 50, null)), 0.000_001);
    assertEquals(0.35, score(book(CURRENT_YEAR - 51, null)), 0.000_001);
  }

  @Test
  void scoresEachDurationPlausibilityBand() {
    assertEquals(1.0, score(book(null, 30)));
    assertEquals(1.0, score(book(null, 1_800)));
    assertEquals(0.6, score(book(null, 15)));
    assertEquals(0.6, score(book(null, 3_000)));
    assertEquals(0.2, score(book(null, 14)));
    assertEquals(0.2, score(book(null, 3_001)));
  }

  @Test
  void combinesAvailableRulesUsingConfiguredWeights() {
    assertEquals(0.72, score(book(CURRENT_YEAR - 20, 600)), 0.000_001);
  }

  @Test
  void renormalizesWhenOnlyOneRuleIsAvailable() {
    assertEquals(0.8, score(book(CURRENT_YEAR - 10, null)), 0.000_001);
    assertEquals(1.0, score(book(null, 600)), 0.000_001);
  }

  @Test
  void reportsUnavailableWhenNeitherRuleHasEvidence() {
    assertFalse(scorer.score(candidate(book(null, null)), RankingContext.empty()).available());
  }

  private double score(AudiobookRecord book) {
    return scorer.score(candidate(book), RankingContext.empty()).value();
  }

  private AudiobookCandidate candidate(AudiobookRecord book) {
    return new AudiobookCandidate(book, null);
  }

  private AudiobookRecord book(Integer publicationYear, Integer durationMinutes) {
    return new AudiobookRecord(
        "book",
        "catalogue",
        "Title",
        List.of("Author"),
        "Description",
        List.of(),
        null,
        durationMinutes,
        publicationYear);
  }

  private RankingScoringProperties properties(double recencyWeight, double durationWeight) {
    return new RankingScoringProperties(
        1.0,
        1.0,
        0.5,
        new RankingScoringProperties.UserPreferenceWeights(0.6, 0.4),
        new RankingScoringProperties.UniversalRuleWeights(recencyWeight, durationWeight));
  }
}
