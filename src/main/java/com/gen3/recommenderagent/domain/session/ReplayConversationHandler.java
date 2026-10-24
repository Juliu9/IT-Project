package com.gen3.recommenderagent.domain.session;

import static com.gen3.recommenderagent.domain.session.ConversationHistory.historyEntry;
import static com.gen3.recommenderagent.domain.session.ConversationHistory.latestSearch;
import static com.gen3.recommenderagent.domain.session.ConversationRules.decisionFor;

import com.gen3.recommenderagent.domain.Intent;
import java.util.List;

/** Replays completed outcomes or the displayed page without running the original action again. */
public final class ReplayConversationHandler implements ConversationHandler {
  public java.util.Set<Intent> intents() {
    return java.util.Set.of(Intent.REPEAT, Intent.REPEAT_RECOMMENDATIONS);
  }

  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (session == null) {
      return effective;
    }
    if (effective.getIntent() == Intent.REPEAT) {
      return resolveRepeat(session, effective);
    }
    if (effective.getIntent() == Intent.REPEAT_RECOMMENDATIONS) {
      ConversationDraft previous = latestSearch(session);
      ConversationDraft repeated = restoreRequest(previous, effective);
      repeated.setPreferenceClarificationRequired(false);
      repeated.setSearchClarificationRequired(false);
      repeated.setNoMoreRecommendations(
          previous != null
              && (previous.isNoMoreRecommendations() || previous.getIntent() == Intent.MORE_RESULTS)
              && repeated.getRecommendations().isEmpty());
      repeated.setDecision(decisionFor(repeated.getRecommendations(), repeated));
      return repeated;
    }
    return effective;
  }

  private ConversationDraft resolveRepeat(Session session, ConversationDraft incoming) {
    ConversationDraft previous =
        session.getLastRequest() == null ? null : session.getLastRequest().toDraft();
    ConversationDecision decision = session.getLastDecision();
    if (previous == null && session.getRequests() != null) {
      // Sessions saved before replay snapshots can still repeat their latest retained request.
      previous =
          session.getRequests().stream()
              .filter(java.util.Objects::nonNull)
              .reduce((first, second) -> second)
              .map(ConversationTurn::toDraft)
              .orElse(null);
      decision = null;
    }
    if (previous == null) {
      incoming.setRecommendations(List.of());
      incoming.setDecision(ConversationDecision.NOTHING_TO_REPEAT);
      return incoming;
    }
    ConversationDraft repeated = restoreRequest(previous, incoming);
    repeated.setDecision(
        decision == null ? decisionFor(repeated.getRecommendations(), previous) : decision);
    return repeated;
  }

  private ConversationDraft restoreRequest(ConversationDraft previous, ConversationDraft incoming) {
    ConversationDraft repeated =
        previous == null ? new ConversationDraft() : historyEntry(previous).toDraft();
    repeated.setIntent(incoming.getIntent());
    repeated.setRequestId(incoming.getRequestId());
    repeated.setCreatedAt(incoming.getCreatedAt());
    repeated.setRawText(incoming.getRawText());
    if (repeated.getRecommendations() == null) {
      repeated.setRecommendations(List.of());
    }
    return repeated;
  }

  public boolean remembersTurn(ConversationDraft result) {
    return result.getIntent() != Intent.REPEAT;
  }

  public String retrievalMode(ConversationDraft result) {
    return result.getIntent() == Intent.REPEAT
        ? "SESSION_REPEAT"
        : "SESSION_REPEAT_RECOMMENDATIONS";
  }
}
