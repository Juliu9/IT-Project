package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.embedding.VectorMath;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import org.springframework.stereotype.Component;

/** Rewards candidates similar to a user's aggregated favourites and recent listening history. */
@Component
public class UserPreferenceRankingStrategy {

  static final double FAVOURITES_WEIGHT = 0.15;
  static final double HISTORY_WEIGHT = 0.10;

  public double adjustment(AudiobookCandidate candidate, UserPreferenceEmbedding userPreferences) {
    if (candidate == null
        || candidate.embedding() == null
        || candidate.embedding().length == 0
        || userPreferences == null) {
      return 0.0;
    }
    return FAVOURITES_WEIGHT * similarity(candidate.embedding(), userPreferences.favouritesVector())
        + HISTORY_WEIGHT * similarity(candidate.embedding(), userPreferences.historyVector());
  }

  private double similarity(float[] candidate, float[] preference) {
    if (preference == null || preference.length != candidate.length) {
      return 0.0;
    }
    return Math.max(0.0, VectorMath.dotProduct(candidate, preference));
  }
}
