package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.embedding.VectorMath;
import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** Calculates the positive reward and negative penalty for one audiobook vector. */
@Component
public class SemanticQueryRankingStrategy implements RankingStrategy {

  static final double POSITIVE_WEIGHT = 0.25;
  static final double NEGATIVE_WEIGHT = 0.35;
  private static final int MAX_RESULTS = 5;

  /** Preserves the legacy strategy entry point when no preference evidence is available. */
  @Override
  public List<Recommendation> rank(List<AudiobookCandidate> candidates, int requestedLimit) {
    return rank(candidates, requestedLimit, SemanticQueryVectors.empty());
  }

  /** Ranks candidates only by preference evidence; hybrid ranking also includes relevance. */
  public List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, SemanticQueryVectors vectors) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    int limit = Math.min(Math.max(requestedLimit, 1), MAX_RESULTS);
    List<AudiobookCandidate> ranked =
        candidates.stream()
            .filter(this::validCandidate)
            .sorted(Comparator.comparingDouble(candidate -> -adjustment(candidate, vectors)))
            .limit(limit)
            .toList();
    java.util.ArrayList<Recommendation> recommendations = new java.util.ArrayList<>(ranked.size());
    for (int index = 0; index < ranked.size(); index++) {
      AudiobookCandidate candidate = ranked.get(index);
      recommendations.add(
          new Recommendation(
              candidate.audiobook().id(),
              index + 1,
              adjustment(candidate, vectors),
              candidate.audiobook().title()));
    }
    return recommendations;
  }

  /** Returns a weighted reward for positive proximity minus the negative proximity penalty. */
  public double adjustment(AudiobookCandidate candidate, SemanticQueryVectors vectors) {
    if (!validCandidate(candidate)
        || candidate.embedding() == null
        || candidate.embedding().length == 0
        || vectors == null
        || vectors.isEmpty()) {
      return 0.0;
    }
    double positive = bestSimilarity(candidate.embedding(), vectors.positive());
    double negative = bestSimilarity(candidate.embedding(), vectors.negative());
    return POSITIVE_WEIGHT * positive - NEGATIVE_WEIGHT * negative;
  }

  private double bestSimilarity(float[] candidate, List<float[]> preferences) {
    return preferences.stream()
        .filter(vector -> vector != null && vector.length == candidate.length)
        .mapToDouble(vector -> Math.max(0.0, VectorMath.dotProduct(candidate, vector)))
        .max()
        .orElse(0.0);
  }

  private boolean validCandidate(AudiobookCandidate candidate) {
    return candidate != null
        && candidate.audiobook() != null
        && candidate.audiobook().id() != null
        && !candidate.audiobook().id().isBlank();
  }
}
