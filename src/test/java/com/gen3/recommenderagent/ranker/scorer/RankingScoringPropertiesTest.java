package com.gen3.recommenderagent.ranker.scorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class RankingScoringPropertiesTest {

  @Test
  void bindsEveryWeightFromTheSharedPropertyNamespace() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("ranking.scoring.retrieval-relevance-weight", "2.0")
            .withProperty("ranking.scoring.user-preference-weight", "4.0")
            .withProperty("ranking.scoring.universal-rules-weight", "0.5")
            .withProperty("ranking.scoring.user-preference.favourites-weight", "0.7")
            .withProperty("ranking.scoring.user-preference.history-weight", "0.3")
            .withProperty("ranking.scoring.universal-rules.recency-weight", "0.7")
            .withProperty("ranking.scoring.universal-rules.duration-plausibility-weight", "0.3");

    RankingScoringProperties properties =
        Binder.get(environment)
            .bind("ranking.scoring", Bindable.of(RankingScoringProperties.class))
            .orElseThrow(() -> new AssertionError("Ranking scoring properties did not bind"));

    assertEquals(2.0, properties.retrievalRelevanceWeight());
    assertEquals(4.0, properties.userPreferenceWeight());
    assertEquals(0.5, properties.universalRulesWeight());
    assertEquals(0.7, properties.userPreference().favouritesWeight());
    assertEquals(0.3, properties.userPreference().historyWeight());
    assertEquals(0.7, properties.universalRules().recencyWeight());
    assertEquals(0.3, properties.universalRules().durationPlausibilityWeight());
  }

  @Test
  void rejectsConfigurationWithoutAnEnabledCandidateScorer() {
    RankingScoringProperties.UserPreferenceWeights preferenceWeights =
        new RankingScoringProperties.UserPreferenceWeights(0.6, 0.4);
    RankingScoringProperties.UniversalRuleWeights universalWeights =
        new RankingScoringProperties.UniversalRuleWeights(0.7, 0.3);

    assertThrows(
        IllegalArgumentException.class,
        () -> new RankingScoringProperties(0.0, 0.0, 0.0, preferenceWeights, universalWeights));
  }

  @Test
  void rejectsConfigurationWithoutAnEnabledPreferenceSignal() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RankingScoringProperties.UserPreferenceWeights(0.0, 0.0));
  }

  @Test
  void rejectsConfigurationWithoutAnEnabledUniversalRule() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RankingScoringProperties.UniversalRuleWeights(0.0, 0.0));
  }
}
