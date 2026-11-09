package com.gen3.recommenderagent.ranker.scorer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Registers the default scoring signals using the existing ranking properties. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RankingScoringProperties.class)
class BuiltInScoringConfiguration {

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  WeightedScorer weightedRetrievalRelevance(
      RetrievalRelevanceScorer scorer, RankingScoringProperties properties) {
    return enabled(scorer, properties.retrievalRelevanceWeight());
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 1)
  WeightedScorer weightedUserPreference(
      UserPreferenceScorer scorer, RankingScoringProperties properties) {
    return enabled(scorer, properties.userPreferenceWeight());
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 2)
  WeightedScorer weightedUniversalRules(
      UniversalRulesScorer scorer, RankingScoringProperties properties) {
    return enabled(scorer, properties.universalRulesWeight());
  }

  private WeightedScorer enabled(CandidateScorer scorer, double weight) {
    // Spring omits null factory results from typed bean discovery.
    return weight == 0.0 ? null : new WeightedScorer(scorer, weight);
  }
}
