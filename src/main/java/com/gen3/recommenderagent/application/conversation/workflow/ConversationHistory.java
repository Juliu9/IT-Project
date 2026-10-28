package com.gen3.recommenderagent.application.conversation.workflow;

import static com.gen3.recommenderagent.application.conversation.workflow.ConversationRules.isSearchContext;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.LastOutcome;
import com.gen3.recommenderagent.domain.session.Session;
import java.time.Instant;
import java.util.UUID;

/** Maintains the active search and latest outcome snapshots. */
public final class ConversationHistory {
  private ConversationHistory() {}

  public static void retainRequest(Session session, ConversationTurn request) {
    if (request == null) {
      return;
    }
    if (isSearchContext(request)) {
      session.getActiveSearch().setSearch(request);
    }
    session.setUpdatedAt(Instant.now());
  }

  static void complete(ConversationDraft request) {
    if (request.getRequestId() == null) {
      request.setRequestId(UUID.randomUUID().toString());
    }
    if (request.getCreatedAt() == null) {
      request.setCreatedAt(Instant.now());
    }
  }

  // Persist only reusable context; raw text and the temporary decision stay out of history.
  static ConversationTurn historyEntry(ConversationDraft request) {
    var entry = ConversationDraft.toTurn(request);
    entry.setRawText(null);
    entry.setDecision(null);
    return entry;
  }

  public static ConversationDraft latestSearch(Session session) {
    return latestSearch(session, false);
  }

  /** Unfinished questions must not replace the latest completed page, including empty pages. */
  public static ConversationDraft latestDisplayedSearch(Session session) {
    return latestSearch(session, true);
  }

  private static ConversationDraft latestSearch(Session session, boolean completedOnly) {
    ConversationTurn search = session.getActiveSearch().getSearch();
    if (search == null
        || !isSearchContext(search)
        || (completedOnly
            && (search.isPreferenceClarificationRequired()
                || search.isSearchClarificationRequired()))) {
      return null;
    }
    return ConversationDraft.fromTurn(search);
  }

  static void remember(Session session, ConversationDraft request) {
    session.setLastOutcome(new LastOutcome(historyEntry(request), request.getDecision()));
  }
}
