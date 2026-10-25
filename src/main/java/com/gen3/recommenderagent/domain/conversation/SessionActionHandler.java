package com.gen3.recommenderagent.domain.conversation;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;

/** Groups simple session actions that need no retrieval or separate service. */
public final class SessionActionHandler implements ConversationHandler {
  public java.util.Set<Intent> intents() {
    return java.util.Set.of(
        Intent.CHANGE_COUNT,
        Intent.RESET_PREFERENCES,
        Intent.CLEAR_HISTORY,
        Intent.HELP,
        Intent.CANCEL,
        Intent.UNKNOWN);
  }

  public ConversationDraft resolve(Session session, ConversationDraft input) {
    return input;
  }

  public boolean retainsHistory() {
    return true;
  }

  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    request.setRecommendations(List.of());
    switch (request.getIntent()) {
      case CHANGE_COUNT -> {
        if (request.getBookCount() != null) {
          session.setBookCount(request.getBookCount());
        }
      }
      case CANCEL -> {
        session.setPendingSimilarBook(null);
        session.setAwaitingPreferenceUpdate(false);
        session
            .getRequests()
            .forEach(previous -> previous.setPreferenceClarificationRequired(false));
      }
      case RESET_PREFERENCES -> {
        session.setPreferences(null);
        session.setBookCount(null);
        request.setBookCount(session.getBookCount());
        session.setAwaitingPreferenceUpdate(false);
        session
            .getRequests()
            .forEach(previous -> previous.setPreferenceClarificationRequired(false));
      }
      case CLEAR_HISTORY -> session.clearHistory();
      default -> {}
    }
  }
}
