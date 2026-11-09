package com.gen3.recommenderagent.ranker.scorer;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import com.gen3.shared.audiobookcatalogue.model.AudiobookRecord;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SearchHistoryScorerTest {

  private final SearchHistoryScorer scorer = new SearchHistoryScorer();
  private final RankingContext horrorFan =
      new RankingContext(null, Set.of()).withSearchHistoryTaste(new float[] {1, 0});

  @Test
  void bookMatchingTheTasteScoresHigh() {
    var result = scorer.score(book(new float[] {1, 0}), horrorFan);

    assertThat(result.available()).isTrue();
    assertThat(result.value()).isEqualTo(1.0);
  }

  @Test
  void unrelatedOrOppositeBooksScoreZero() {
    assertThat(scorer.score(book(new float[] {0, 1}), horrorFan).value()).isZero();
    assertThat(scorer.score(book(new float[] {-1, 0}), horrorFan).value()).isZero();
  }

  @Test
  void noTasteMeansNoScore() {
    var noTaste = new RankingContext(null, Set.of());

    assertThat(scorer.score(book(new float[] {1, 0}), noTaste).available()).isFalse();
  }

  @Test
  void differentVectorSizeMeansNoScore() {
    assertThat(scorer.score(book(new float[] {1, 0, 0}), horrorFan).available()).isFalse();
  }

  @Test
  void neverClaimsReadingPreferences() {
    assertThat(scorer.score(book(new float[] {1, 0}), horrorFan).readingPreferencesUsed())
        .isFalse();
  }

  @Test
  void tasteSurvivesTheScoreRangeStep() {
    var ranged = horrorFan.withRetrievalScoreRange(0.1, 0.9);

    assertThat(ranged.searchHistoryTaste()).isSameAs(horrorFan.searchHistoryTaste());
  }

  private AudiobookCandidate book(float[] embedding) {
    var audiobook = new AudiobookRecord("b", "catalogue", "Title", List.of(), null);
    return new AudiobookCandidate(audiobook, 0.5, embedding);
  }
}
