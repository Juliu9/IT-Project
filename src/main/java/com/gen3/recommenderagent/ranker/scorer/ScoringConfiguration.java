package com.gen3.recommenderagent.ranker.scorer;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
      SearchHistoryScorer searchHistoryScorer,
      RankingScoringProperties properties,
      @Value("${ranking.scoring.search-history-weight:0.5}") double searchHistoryWeight) {
    if (!Double.isFinite(searchHistoryWeight) || searchHistoryWeight < 0.0) {
      throw new IllegalArgumentException("search-history-weight must be finite and nonnegative");
    }
    List<WeightedScorer> scorers = new ArrayList<>();
    add(scorers, retrievalRelevanceScorer, properties.retrievalRelevanceWeight());
    add(scorers, userPreferenceScorer, properties.userPreferenceWeight());
    add(scorers, universalRulesScorer, properties.universalRulesWeight());
    // Only scores when a search taste was loaded, so it changes nothing while the feature is off.
    add(scorers, searchHistoryScorer, searchHistoryWeight);
    return new CompositeScorer(scorers);
  }

  /** Registers a scoring signal only when its configured contribution is positive. */
  private void add(List<WeightedScorer> scorers, CandidateScorer scorer, double weight) {
    if (weight > 0.0) {
      scorers.add(new WeightedScorer(scorer, weight));
    }
  }
}
