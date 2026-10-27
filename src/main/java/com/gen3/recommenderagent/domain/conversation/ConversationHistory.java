package com.gen3.recommenderagent.domain.conversation;

import static com.gen3.recommenderagent.domain.conversation.ConversationRules.isSearchContext;

import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import java.time.Instant;
import java.util.UUID;

/** Retains reusable searches and completed replay snapshots independently of intent execution. */
public final class ConversationHistory {
  private ConversationHistory() {}

  public static void retainRequest(Session session, ConversationTurn request) {
    if (request == null) {
      return;
    }
    // Keep an unfinished preference search alongside the last completed search.
    boolean search = isSearchContext(request);
    session
        .getRequests()
        .removeIf(
            existing ->
                isSearchContext(existing) == search
                    && (!request.isPreferenceClarificationRequired()
                        || existing.isPreferenceClarificationRequired()));
    session.getRequests().add(request);
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
    var entry = ConversationTurn.fromDraft(request);
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
    return session.getRequests() == null
        ? null
        : session.getRequests().stream()
            .filter(ConversationRules::isSearchContext)
            .filter(
                turn ->
                    !completedOnly
                        || (!turn.isPreferenceClarificationRequired()
                            && !turn.isSearchClarificationRequired()))
            .reduce((first, second) -> second)
            .map(ConversationTurn::toDraft)
            .orElse(null);
  }

  static void remember(Session session, ConversationDraft request) {
    session.setLastRequest(historyEntry(request));
    session.setLastDecision(request.getDecision());
  }
}
