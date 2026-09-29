package com.gen3.recommenderagent.application;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.gen3.recommenderagent.application.action.ChangeCountAction;
import com.gen3.recommenderagent.application.action.ClearHistoryAction;
import com.gen3.recommenderagent.application.action.MoreResultsAction;
import com.gen3.recommenderagent.application.action.NoOpAction;
import com.gen3.recommenderagent.application.action.RecommendationAction;
import com.gen3.recommenderagent.application.action.RefineAction;
import com.gen3.recommenderagent.application.action.UpdatePreferencesAction;
import com.gen3.recommenderagent.domain.Intent;
import org.junit.jupiter.api.Test;

class ActionRegistryTest {

  @Test
  void mapsRecommendationAndLightweightIntentsToTheirActions() {
    RecommendationAction recommendation = mock(RecommendationAction.class);
    RefineAction refine = mock(RefineAction.class);
    MoreResultsAction moreResults = mock(MoreResultsAction.class);
    ChangeCountAction changeCount = mock(ChangeCountAction.class);
    UpdatePreferencesAction preferences = mock(UpdatePreferencesAction.class);
    ClearHistoryAction clearHistory = mock(ClearHistoryAction.class);
    NoOpAction noOp = mock(NoOpAction.class);
    ActionRegistry registry =
        new ActionRegistry(
            recommendation, refine, moreResults, changeCount, preferences, clearHistory, noOp);

    assertSame(recommendation, registry.get(Intent.RECOMMENDATION));
    assertSame(refine, registry.get(Intent.REFINE));
    assertSame(moreResults, registry.get(Intent.MORE_RESULTS));
    assertSame(changeCount, registry.get(Intent.CHANGE_COUNT));
    assertSame(preferences, registry.get(Intent.UPDATE_PREFERENCES));
    assertSame(clearHistory, registry.get(Intent.CLEAR_HISTORY));
    assertSame(noOp, registry.get(Intent.HELP));
    assertSame(noOp, registry.get(Intent.UNKNOWN));
    assertSame(noOp, registry.get(null));
    for (Intent intent : Intent.values()) {
      assertNotNull(registry.get(intent));
    }
  }
}
