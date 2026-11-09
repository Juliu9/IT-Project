package com.gen3.recommenderagent.ranker.scorer;

import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import com.gen3.shared.vector.VectorMath;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/** Scores how close each book is to the user's search taste (MAPA-30). */
@Component
public class SearchHistoryScorer implements CandidateScorer {

  @Bean
  WeightedScorer weightedSearchHistory(
      @Value("${ranking.scoring.search-history-weight:0.5}") double weight) {
    return WeightedScorer.ifEnabled(this, weight);
  }

  @Override
  public ScoreResult score(AudiobookCandidate candidate, RankingContext context) {
    if (candidate == null || context == null) {
      return ScoreResult.unavailable();
    }
    float[] book = candidate.embedding();
    float[] taste = context.searchHistoryTaste();
    if (book == null || taste == null || taste.length == 0 || book.length != taste.length) {
      return ScoreResult.unavailable();
    }
    // Searches aren't reading history, so this never claims the reading-preferences flag.
    return ScoreResult.available(Math.clamp(VectorMath.dotProduct(book, taste), 0.0, 1.0));
  }
}
