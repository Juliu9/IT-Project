package com.gen3.recommenderagent.application.conversation.workflow;

import static com.gen3.recommenderagent.application.conversation.workflow.ConversationHistory.historyEntry;
import static com.gen3.recommenderagent.application.conversation.workflow.ConversationHistory.latestDisplayedSearch;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.List;

/** Replays completed outcomes or the displayed page without running the original action again. */
public final class ReplayConversationHandler implements ConversationHandler {
  /** Returns the conversation actions handled by this handler. */
  public java.util.Set<ConversationAction> intents() {
    return java.util.Set.of(ConversationAction.REPEAT, ConversationAction.REPEAT_RECOMMENDATIONS);
  }

  /** Restores the last result or retained recommendation page without consuming new results. */
  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (session == null) {
      return effective;
    }
    if (effective.getIntent() == ConversationAction.REPEAT) {
      return resolveRepeat(session, effective);
    }
    if (effective.getIntent() == ConversationAction.REPEAT_RECOMMENDATIONS) {
      ConversationDraft previous = latestDisplayedSearch(session);
      ConversationDraft repeated = restoreRequest(previous, effective);
      repeated.setPendingQuestion(null);
      repeated.setPreferenceClarificationRequired(false);
      repeated.setSearchClarificationRequired(false);
      repeated.setNoMoreRecommendations(
          previous != null
              && (previous.isNoMoreRecommendations()
                  || previous.getIntent() == ConversationAction.MORE_RESULTS)
              && repeated.getRecommendations().isEmpty());
      if (previous == null) {
        repeated.setOutcome(
            com.gen3.recommenderagent.domain.conversation.ConversationOutcome
                .NO_RECOMMENDATIONS_TO_REPEAT);
      }
      return repeated;
    }
    return effective;
  }

  /** Restores the most recent repeatable outcome or reports that none exists. */
  private ConversationDraft resolveRepeat(Session session, ConversationDraft incoming) {
    var outcome = session.getLastOutcome();
    ConversationDraft previous =
        outcome == null || outcome.turn() == null
            ? latestDisplayedSearch(session)
            : ConversationDraft.fromTurn(outcome.turn());
    var savedOutcome = outcome == null ? null : outcome.outcome();
    if (previous == null) {
      incoming.setRecommendations(List.of());
      incoming.setOutcome(ConversationOutcome.NOTHING_TO_REPEAT);
      return incoming;
    }
    ConversationDraft repeated = restoreRequest(previous, incoming);
    repeated.setOutcome(savedOutcome);
    repeated.setResponseAction(previous.getIntent());
    return repeated;
  }

  /** Rebuilds a retained response using the current replay request's identity. */
  private ConversationDraft restoreRequest(ConversationDraft previous, ConversationDraft incoming) {
    ConversationDraft repeated =
        previous == null
            ? new ConversationDraft()
            : ConversationDraft.fromTurn(historyEntry(previous));
    repeated.setIntent(incoming.getIntent());
    repeated.setRequestId(incoming.getRequestId());
    repeated.setCreatedAt(incoming.getCreatedAt());
    repeated.setRawText(incoming.getRawText());
    if (repeated.getRecommendations() == null) {
      repeated.setRecommendations(List.of());
    }
    return repeated;
  }

  /** Reports whether the completed result should replace the repeatable last outcome. */
  public boolean remembersTurn(ConversationDraft result) {
    return result.getIntent() != ConversationAction.REPEAT;
  }

  /** Returns the telemetry label describing how this action obtained its results. */
  public String retrievalMode(ConversationDraft result) {
    return result.getIntent() == ConversationAction.REPEAT
        ? "SESSION_REPEAT"
        : "SESSION_REPEAT_RECOMMENDATIONS";
  }
}
