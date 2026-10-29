package com.gen3.recommenderagent.application.conversation.workflow;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.List;

/** Groups simple session actions that need no retrieval or separate service. */
public final class SessionActionHandler implements ConversationHandler {
  public java.util.Set<ConversationAction> intents() {
    return java.util.Set.of(
        ConversationAction.CHANGE_COUNT,
        ConversationAction.RESET_PREFERENCES,
        ConversationAction.CLEAR_HISTORY,
        ConversationAction.HELP,
        ConversationAction.CANCEL,
        ConversationAction.UNKNOWN);
  }

  public ConversationDraft resolve(Session session, ConversationDraft input) {
    if (input.getIntent() == ConversationAction.CHANGE_COUNT
        && (input.isInvalidBookCount()
            || input.getBookCount() == null
            || input.getBookCount() <= 0)) {
      input.setInvalidBookCount(true);
      input.setBookCount(session.getBookCount());
    }
    return input;
  }

  public boolean retainsHistory() {
    return true;
  }

  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    request.setRecommendations(List.of());
    switch (request.getIntent()) {
      case CHANGE_COUNT -> {
        if (!request.isInvalidBookCount() && request.getBookCount() != null) {
          session.setBookCount(request.getBookCount());
        }
      }
      case CANCEL -> {
        session.setPendingQuestion(null);
        session.clearPreferenceQuestions();
      }
      case RESET_PREFERENCES -> {
        session.setPreferences(null);
        session.setBookCount(null);
        request.setBookCount(session.getBookCount());
        session.clearPreferenceQuestions();
      }
      case CLEAR_HISTORY -> session.clearHistory();
      default -> {}
    }
  }
}
