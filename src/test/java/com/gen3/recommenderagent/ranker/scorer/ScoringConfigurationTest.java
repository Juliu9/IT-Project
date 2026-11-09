package com.gen3.recommenderagent.ranker.scorer;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ScoringConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              ScoringConfiguration.class,
              BuiltInScoringConfiguration.class,
              RetrievalRelevanceScorer.class,
              UserPreferenceScorer.class,
              UniversalRulesScorer.class)
          .withPropertyValues(
              "ranking.scoring.retrieval-relevance-weight=1.0",
              "ranking.scoring.user-preference-weight=0.0",
              "ranking.scoring.universal-rules-weight=0.0",
              "ranking.scoring.user-preference.favourites-weight=1.0",
              "ranking.scoring.universal-rules.recency-weight=1.0");

  @Test
  void composesDefaultsAndDiscoveredExtensionWhileOmittingDisabledScorers() {
    var candidate = new AudiobookCandidate(null, 10.0);
    var rankingContext = RankingContext.empty().withRetrievalScoreRange(0.0, 10.0);
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(CompositeScorer.class);
          assertThat(context.getBeansOfType(WeightedScorer.class))
              .containsOnlyKeys("weightedRetrievalRelevance");
          assertThat(context.getBean(CompositeScorer.class).score(candidate, rankingContext))
              .isEqualTo(ScoreResult.available(1.0));
        });

    runner
        .withUserConfiguration(ExtensionConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(CompositeScorer.class);
              assertThat(context.getBeansOfType(WeightedScorer.class))
                  .containsOnlyKeys("weightedRetrievalRelevance", "extensionScorer");
              assertThat(context.getBean(CompositeScorer.class).score(candidate, rankingContext))
                  .isEqualTo(ScoreResult.available(0.5));
            });
  }

  @Test
  void discoversSearchHistoryWeightThroughTheScorerComponent() {
    runner
        .withUserConfiguration(SearchHistoryScorer.class)
        .withPropertyValues("ranking.scoring.search-history-weight=0.25")
        .run(
            context -> {
              assertThat(context).hasSingleBean(CompositeScorer.class);
              var scorer = context.getBean("weightedSearchHistory", WeightedScorer.class);
              assertThat(scorer.scorer()).isInstanceOf(SearchHistoryScorer.class);
              assertThat(scorer.weight()).isEqualTo(0.25);
            });
  }

  @Test
  void omitsSearchHistoryWhenItsWeightIsZero() {
    runner
        .withUserConfiguration(SearchHistoryScorer.class)
        .withPropertyValues("ranking.scoring.search-history-weight=0.0")
        .run(
            context ->
                assertThat(context.getBeansOfType(WeightedScorer.class))
                    .containsOnlyKeys("weightedRetrievalRelevance"));
  }

  @Configuration(proxyBeanMethods = false)
  static class ExtensionConfiguration {
    @Bean
    WeightedScorer extensionScorer() {
      return new WeightedScorer((candidate, context) -> ScoreResult.available(0.25), 2.0);
    }
  }
}
