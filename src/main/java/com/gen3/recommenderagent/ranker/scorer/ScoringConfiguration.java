package com.gen3.recommenderagent.ranker.scorer;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the application's final scorer from every independently weighted scoring signal. */
@Configuration
@EnableConfigurationProperties(RankingScoringProperties.class)
public class ScoringConfiguration {

  /** Combines the configured scoring signals with their positive relative weights. */
  @Bean
  public CompositeScorer compositeScorer(
      RetrievalRelevanceScorer retrievalRelevanceScorer,
      UserPreferenceScorer userPreferenceScorer,
      UniversalRulesScorer universalRulesScorer,
      RankingScoringProperties properties) {
    List<WeightedScorer> scorers = new ArrayList<>();
    add(scorers, retrievalRelevanceScorer, properties.retrievalRelevanceWeight());
    add(scorers, userPreferenceScorer, properties.userPreferenceWeight());
    add(scorers, universalRulesScorer, properties.universalRulesWeight());
    return new CompositeScorer(scorers);
  }

  /** Registers a scoring signal only when its configured contribution is positive. */
  private void add(List<WeightedScorer> scorers, CandidateScorer scorer, double weight) {
    if (weight > 0.0) {
      scorers.add(new WeightedScorer(scorer, weight));
    }
  }
}
