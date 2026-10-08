package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.embedding.VectorMath;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import java.util.List;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

/** Scores positive semantic matches and negative-query matches on a signed scale. */
@Component
public class SemanticScorer implements CandidateScorer {

  @Override
  public ScoreResult score(AudiobookCandidate candidate, RankingContext context) {
    if (candidate == null
        || candidate.embedding() == null
        || candidate.embedding().length == 0
        || context == null) {
      return ScoreResult.unavailable();
    }

    SemanticQueryVectors vectors = context.queryVectors();
    OptionalDouble positive = bestSimilarity(candidate.embedding(), vectors.positive());
    OptionalDouble negative = bestSimilarity(candidate.embedding(), vectors.negative());
    if (positive.isEmpty() && negative.isEmpty()) {
      return ScoreResult.unavailable();
    }

    double positiveScore = positive.orElse(0.0);
    double negativeScore = negative.orElse(0.0);
    return ScoreResult.available(Math.clamp(positiveScore - negativeScore, -1.0, 1.0));
  }

  private OptionalDouble bestSimilarity(float[] candidate, List<float[]> queries) {
    return queries.stream()
        .filter(query -> query != null && query.length == candidate.length)
        .mapToDouble(query -> Math.clamp(VectorMath.dotProduct(candidate, query), 0.0, 1.0))
        .max();
  }
}
