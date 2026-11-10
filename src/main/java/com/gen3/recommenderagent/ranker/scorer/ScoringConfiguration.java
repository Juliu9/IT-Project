package com.gen3.recommenderagent.ranker.scorer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composes the application's registered scoring signals. */
@Configuration(proxyBeanMethods = false)
public class ScoringConfiguration {

  /** Combines all enabled weighted scorers in Spring order. */
  @Bean
  public CompositeScorer compositeScorer(ObjectProvider<WeightedScorer> scorers) {
    return new CompositeScorer(scorers.orderedStream().toList());
  }
}
