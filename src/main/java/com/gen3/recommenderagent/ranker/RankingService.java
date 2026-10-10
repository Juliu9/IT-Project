package com.gen3.recommenderagent.ranker;

import static com.gen3.recommenderagent.common.BookCountPolicy.clamp;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.ranker.scorer.CompositeScorer;
import com.gen3.recommenderagent.ranker.scorer.ScoreResult;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Applies exclusions, combines candidate scores, and creates ranked recommendations. */
@Service
public class RankingService implements Ranker {

  private final CompositeScorer compositeScorer;

  public RankingService(CompositeScorer compositeScorer) {
    this.compositeScorer = compositeScorer;
  }

  /** Preserves retrieval order when no scorer has usable evidence. */
  @Override
  public List<Recommendation> rank(List<AudiobookCandidate> candidates, int requestedLimit) {
    return rank(candidates, requestedLimit, RankingContext.empty());
  }

  /** Uses semantic-query vectors for polarity-aware reranking when available. */
  @Override
  public List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, SemanticQueryVectors vectors) {
    return rank(candidates, requestedLimit, new RankingContext(vectors, null, Set.of()));
  }

  /**
   * Scores eligible candidates, orders them by score, and creates the requested recommendations.
   */
  @Override
  public List<Recommendation> rank(
      List<AudiobookCandidate> candidates, int requestedLimit, RankingContext context) {
    RankingContext effective = context == null ? RankingContext.empty() : context;
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }

    int limit = clamp(requestedLimit);
    Set<String> seenBookIds = new HashSet<>();
    List<AudiobookCandidate> eligible =
        candidates.stream()
            .filter(candidate -> candidate != null && candidate.audiobook() != null)
            .filter(candidate -> candidate.audiobook().id() != null)
            .filter(candidate -> !candidate.audiobook().id().isBlank())
            .filter(candidate -> !effective.excludedBookIds().contains(candidate.audiobook().id()))
            .filter(candidate -> seenBookIds.add(candidate.audiobook().id()))
            .toList();
    List<Double> retrievalScores =
        eligible.stream()
            .map(AudiobookCandidate::score)
            .filter(Objects::nonNull)
            .filter(Double::isFinite)
            .toList();
    Double minimumRetrievalScore =
        retrievalScores.isEmpty()
            ? null
            : retrievalScores.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    Double maximumRetrievalScore =
        retrievalScores.isEmpty()
            ? null
            : retrievalScores.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    RankingContext scoringContext =
        effective.withRetrievalScoreRange(minimumRetrievalScore, maximumRetrievalScore);
    List<ScoredCandidate> scoredCandidates = new ArrayList<>();
    for (AudiobookCandidate candidate : eligible) {
      scoredCandidates.add(
          new ScoredCandidate(candidate, compositeScorer.score(candidate, scoringContext)));
    }

    scoredCandidates.sort(
        Comparator.comparingDouble(
                (ScoredCandidate item) -> item.score().available() ? item.score().value() : 0.0)
            .reversed());
    boolean hasScoredCandidate =
        scoredCandidates.stream().anyMatch(item -> item.score().available());
    List<Recommendation> recommendations =
        new ArrayList<>(Math.min(limit, scoredCandidates.size()));
    for (ScoredCandidate item : scoredCandidates.stream().limit(limit).toList()) {
      AudiobookCandidate candidate = item.candidate();
      int rank = recommendations.size() + 1;
      Double score;
      if (item.score().available()) {
        score = item.score().value();
      } else if (hasScoredCandidate) {
        score = 0.0;
      } else {
        score = candidate.score();
      }
      recommendations.add(
          new Recommendation(
              candidate.audiobook().id(), rank, score, candidate.audiobook().title()));
    }
    return recommendations;
  }

  private record ScoredCandidate(AudiobookCandidate candidate, ScoreResult score) {}
}
