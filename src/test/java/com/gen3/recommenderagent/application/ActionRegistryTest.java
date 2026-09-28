package com.gen3.recommenderagent.application;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.gen3.recommenderagent.application.action.ClearHistoryAction;
import com.gen3.recommenderagent.application.action.NoOpAction;
import com.gen3.recommenderagent.application.action.RecommendationAction;
import com.gen3.recommenderagent.application.action.UpdatePreferencesAction;
import com.gen3.recommenderagent.domain.Intent;
import org.junit.jupiter.api.Test;

class ActionRegistryTest {

  @Test
  void mapsRecommendationAndLightweightIntentsToTheirActions() {
    RecommendationAction recommendation = mock(RecommendationAction.class);
    UpdatePreferencesAction preferences = mock(UpdatePreferencesAction.class);
    ClearHistoryAction clearHistory = mock(ClearHistoryAction.class);
    NoOpAction noOp = mock(NoOpAction.class);
    ActionRegistry registry = new ActionRegistry(recommendation, preferences, clearHistory, noOp);

    assertSame(recommendation, registry.get(Intent.NEW_RECOMMENDATION));
    assertSame(recommendation, registry.get(Intent.SIMILAR_TO_BOOK));
    assertSame(preferences, registry.get(Intent.UPDATE_PREFERENCES));
    assertSame(clearHistory, registry.get(Intent.CLEAR_HISTORY));
    assertSame(noOp, registry.get(Intent.HELP));
    assertSame(noOp, registry.get(Intent.LIKE_RECOMMENDATION));
    assertSame(noOp, registry.get(null));
    for (Intent intent : Intent.values()) {
      assertNotNull(registry.get(intent));
    }
  }
}
