package com.gen3.recommenderagent.ranker.strategy;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Configures the selectable top-level scorers and their application-owned weights. */
@Configuration
public class ScoringConfiguration {

  @Bean
  @Primary
  public CandidateScorer compositeScorer(
      RetrievalRelevanceScorer retrievalRelevanceScorer,
      SemanticScorer semanticScorer,
      HybridScorer hybridScorer,
      UserPreferenceScorer userPreferenceScorer,
      @Value("${ranking.scoring.retrieval-relevance-weight:1.0}") double retrievalRelevanceWeight,
      @Value("${ranking.scoring.semantic-weight:1.0}") double semanticWeight,
      @Value("${ranking.scoring.hybrid-weight:0.0}") double hybridWeight,
      @Value("${ranking.scoring.user-preference-weight:1.0}") double userPreferenceWeight) {
    if (hybridWeight > 0.0 && (retrievalRelevanceWeight > 0.0 || semanticWeight > 0.0)) {
      throw new IllegalArgumentException(
          "Use the hybrid scorer instead of separately weighting retrieval relevance or semantic scoring");
    }
    List<WeightedScorer> scorers = new ArrayList<>();
    add(scorers, retrievalRelevanceScorer, retrievalRelevanceWeight);
    add(scorers, semanticScorer, semanticWeight);
    add(scorers, hybridScorer, hybridWeight);
    add(scorers, userPreferenceScorer, userPreferenceWeight);
    return new CompositeScorer(scorers);
  }

  private void add(List<WeightedScorer> scorers, CandidateScorer scorer, double weight) {
    if (!Double.isFinite(weight) || weight < 0.0) {
      throw new IllegalArgumentException(
          "Configured scorer weights must be finite and nonnegative");
    }
    if (weight > 0.0) {
      scorers.add(new WeightedScorer(scorer, weight));
    }
  }
}
