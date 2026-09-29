package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.application.action.ChangeCountAction;
import com.gen3.recommenderagent.application.action.ClearHistoryAction;
import com.gen3.recommenderagent.application.action.MoreResultsAction;
import com.gen3.recommenderagent.application.action.NoOpAction;
import com.gen3.recommenderagent.application.action.RecommendationAction;
import com.gen3.recommenderagent.application.action.RefineAction;
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
      RefineAction refineAction,
      MoreResultsAction moreResultsAction,
      ChangeCountAction changeCountAction,
      UpdatePreferencesAction updatePreferencesAction,
      ClearHistoryAction clearHistoryAction,
      NoOpAction noOpAction) {
    EnumMap<Intent, IntentAction> registry = new EnumMap<>(Intent.class);

    registry.put(Intent.RECOMMENDATION, recommendationAction);
    registry.put(Intent.REFINE, refineAction);
    registry.put(Intent.MORE_RESULTS, moreResultsAction);
    registry.put(Intent.CHANGE_COUNT, changeCountAction);
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
}
