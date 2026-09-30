package com.gen3.recommenderagent.ranker.strategy;

import static com.gen3.recommenderagent.common.BookCountPolicy.clamp;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Blends normalized database relevance with positive and negative preference similarity. */
@Component
public class HybridRankingStrategy implements RankingStrategy {

  private static final double RELEVANCE_WEIGHT = 0.60;
  private final SemanticQueryRankingStrategy semanticQueryRankingStrategy;
  private final UserPreferenceRankingStrategy userPreferenceRankingStrategy;

  public HybridRankingStrategy(
      SemanticQueryRankingStrategy semanticQueryRankingStrategy,
      UserPreferenceRankingStrategy userPreferenceRankingStrategy) {
    this.semanticQueryRankingStrategy = semanticQueryRankingStrategy;
    this.userPreferenceRankingStrategy = userPreferenceRankingStrategy;
  }

  /** Preserves the legacy strategy entry point when no preference evidence is available. */
  @Override
  public List<Recommendation> rank(List<AudiobookCandidate> candidates, int requestedLimit) {
    return rank(candidates, requestedLimit, SemanticQueryVectors.empty());
  }

  /** Reranks unique candidates using relevance plus positive reward and negative penalty. */
  public List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, SemanticQueryVectors vectors) {
    return rank(candidates, requestedLimit, new RankingContext(vectors, null, Set.of()));
  }

  /** Reranks using request semantics, persistent user vectors, and normalized relevance. */
  public List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, RankingContext context) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    int limit = clamp(requestedLimit);
    List<AudiobookCandidate> unique = uniqueCandidates(candidates);
    double maximumScore = maximumScore(unique);
    List<ScoredCandidate> scored =
        unique.stream()
            .map(
                candidate ->
                    new ScoredCandidate(
                        candidate,
                        RELEVANCE_WEIGHT * normalizedRelevance(candidate.score(), maximumScore)
                            + semanticQueryRankingStrategy.adjustment(
                                candidate, context.queryVectors())
                            + userPreferenceRankingStrategy.adjustment(
                                candidate, context.userPreferences())))
            .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
            .limit(limit)
            .toList();

    List<Recommendation> recommendations = new ArrayList<>(scored.size());
    for (int index = 0; index < scored.size(); index++) {
      ScoredCandidate item = scored.get(index);
      recommendations.add(
          new Recommendation(
              item.candidate().audiobook().id(),
              index + 1,
              item.score(),
              item.candidate().audiobook().title()));
    }
    return recommendations;
  }

  private List<AudiobookCandidate> uniqueCandidates(List<AudiobookCandidate> candidates) {
    Set<String> seen = new HashSet<>();
    return candidates.stream()
        .filter(candidate -> candidate != null && candidate.audiobook() != null)
        .filter(
            candidate ->
                candidate.audiobook().id() != null
                    && !candidate.audiobook().id().isBlank()
                    && seen.add(candidate.audiobook().id()))
        .toList();
  }

  private double maximumScore(List<AudiobookCandidate> candidates) {
    return candidates.stream()
        .map(AudiobookCandidate::score)
        .filter(java.util.Objects::nonNull)
        .mapToDouble(Double::doubleValue)
        .max()
        .orElse(0.0);
  }

  private double normalizedRelevance(Double score, double maximumScore) {
    if (score == null || maximumScore <= 0.0) {
      return 0.0;
    }
    return Math.clamp(score / maximumScore, 0.0, 1.0);
  }

  private record ScoredCandidate(AudiobookCandidate candidate, double score) {}
}
