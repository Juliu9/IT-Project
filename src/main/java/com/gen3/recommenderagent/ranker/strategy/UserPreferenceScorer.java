package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.embedding.VectorMath;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Scores positive similarity to the user's favourites and listening history. */
@Component
public class UserPreferenceScorer implements CandidateScorer {

  private static final double FAVOURITES_WEIGHT = 0.60;
  private static final double HISTORY_WEIGHT = 0.40;

  @Override
  public ScoreResult score(AudiobookCandidate candidate, RankingContext context) {
    if (candidate == null
        || candidate.embedding() == null
        || candidate.embedding().length == 0
        || context == null
        || context.userPreferences() == null) {
      return ScoreResult.unavailable();
    }

    UserPreferenceEmbedding preferences = context.userPreferences();
    List<WeightedSimilarity> similarities = new ArrayList<>(2);
    addSimilarity(
        similarities, candidate.embedding(), preferences.favouritesVector(), FAVOURITES_WEIGHT);
    addSimilarity(similarities, candidate.embedding(), preferences.historyVector(), HISTORY_WEIGHT);
    if (similarities.isEmpty()) {
      return ScoreResult.unavailable();
    }

    double total = similarities.stream().mapToDouble(WeightedSimilarity::weightedScore).sum();
    double availableWeight = similarities.stream().mapToDouble(WeightedSimilarity::weight).sum();
    return ScoreResult.available(Math.clamp(total / availableWeight, 0.0, 1.0));
  }

  private void addSimilarity(
      List<WeightedSimilarity> similarities, float[] candidate, float[] preference, double weight) {
    if (preference != null && preference.length == candidate.length) {
      double similarity = Math.clamp(VectorMath.dotProduct(candidate, preference), 0.0, 1.0);
      similarities.add(new WeightedSimilarity(similarity * weight, weight));
    }
  }

  private record WeightedSimilarity(double weightedScore, double weight) {}
}
