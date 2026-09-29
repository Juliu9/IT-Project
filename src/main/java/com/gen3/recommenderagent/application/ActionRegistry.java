package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.application.action.ClearHistoryAction;
import com.gen3.recommenderagent.application.action.NoOpAction;
import com.gen3.recommenderagent.application.action.RecommendationAction;
import com.gen3.recommenderagent.application.action.UpdatePreferencesAction;
import com.gen3.recommenderagent.domain.Intent;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Resolves every parsed intent to one application action. */
@Component
public class ActionRegistry {

  private final Map<Intent, IntentAction> actions;

  public ActionRegistry(
      RecommendationAction recommendationAction,
      UpdatePreferencesAction updatePreferencesAction,
      ClearHistoryAction clearHistoryAction,
      NoOpAction noOpAction) {
    EnumMap<Intent, IntentAction> registry = new EnumMap<>(Intent.class);

    registerRecommendations(registry, recommendationAction);
    registry.put(Intent.UPDATE_PREFERENCES, updatePreferencesAction);
    registry.put(Intent.CLEAR_HISTORY, clearHistoryAction);
    registry.put(Intent.HELP, noOpAction);

    for (Intent intent : Intent.values()) {
      registry.putIfAbsent(intent, noOpAction);
    }
    this.actions = Map.copyOf(registry);
  }

  public IntentAction get(Intent intent) {
    return actions.get(intent == null ? Intent.UNKNOWN : intent);
  }

  private void registerRecommendations(
      Map<Intent, IntentAction> registry, RecommendationAction recommendationAction) {
    registry.put(Intent.NEW_RECOMMENDATION, recommendationAction);
    registry.put(Intent.SIMILAR_TO_BOOK, recommendationAction);
    registry.put(Intent.SIMILAR_TO_AUTHOR, recommendationAction);
    registry.put(Intent.REFINE_RECOMMENDATION, recommendationAction);
    registry.put(Intent.FILTER_BY_LENGTH, recommendationAction);
    registry.put(Intent.FILTER_BY_AUTHOR, recommendationAction);
    registry.put(Intent.FILTER_BY_NARRATOR, recommendationAction);
    registry.put(Intent.BOOK_DETAILS, recommendationAction);
  }
}
