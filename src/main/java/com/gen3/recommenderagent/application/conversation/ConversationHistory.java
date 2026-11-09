package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.LastOutcome;
import com.gen3.recommenderagent.domain.session.Session;
import java.time.Instant;
import java.util.UUID;

/** Maintains the active search and latest outcome snapshots. */
public final class ConversationHistory {
  /** Prevents instantiation of this utility class. */
  private ConversationHistory() {}

  /** Stores active-search context only when the executing handler marks it reusable. */
  public static void retainRequest(
      Session session, ConversationTurn request, boolean searchAction) {
    if (request == null) {
      return;
    }
    if (searchAction) {
      session.getActiveSearch().setSearch(request);
    }
    session.setUpdatedAt(Instant.now());
  }

  /** Fills missing request identity and creation time before retaining a completed action. */
  public static void complete(ConversationDraft request) {
    if (request.getRequestId() == null) {
      request.setRequestId(UUID.randomUUID().toString());
    }
    if (request.getCreatedAt() == null) {
      request.setCreatedAt(Instant.now());
    }
  }

  // Persist only reusable context; raw text and the temporary decision stay out of history.
  /** Creates a reusable turn snapshot without raw text or its temporary outcome field. */
  public static ConversationTurn historyEntry(ConversationDraft request) {
    var entry = ConversationDraft.toStoredTurn(request);
    entry.setOutcome(null);
    return entry;
  }

  /** Restores the retained active search, optionally requiring a completed displayed page. */
  public static ConversationDraft latestSearch(Session session) {
    return latestSearch(session, false);
  }

  /** Unfinished questions must not replace the latest completed page, including empty pages. */
  public static ConversationDraft latestDisplayedSearch(Session session) {
    return latestSearch(session, true);
  }

  /** Restores the retained active search, optionally requiring a completed displayed page. */
  private static ConversationDraft latestSearch(Session session, boolean completedOnly) {
    ConversationTurn search = session.getActiveSearch().getSearch();
    if (search == null
        || (completedOnly
            && (search.isPreferenceClarificationRequired()
                || search.isSearchClarificationRequired()))) {
      return null;
    }
    return ConversationDraft.fromTurn(search);
  }

  /** Stores the completed action and outcome as the session's repeatable last result. */
  public static void remember(Session session, ConversationDraft request) {
    session.setLastOutcome(new LastOutcome(historyEntry(request), request.getOutcome()));
  }

  /** Rebuilds a retained response using the current replay request's identity. */
  public static ConversationDraft restoreRequest(
      ConversationDraft previous, ConversationDraft incoming) {
    ConversationDraft repeated =
        previous == null
            ? new ConversationDraft()
            : ConversationDraft.fromTurn(historyEntry(previous));
    repeated.setIntent(incoming.getIntent());
    repeated.setRequestId(incoming.getRequestId());
    repeated.setCreatedAt(incoming.getCreatedAt());
    repeated.setRawText(incoming.getRawText());
    if (repeated.getRecommendations() == null) {
      repeated.setRecommendations(java.util.List.of());
    }
    return repeated;
  }

  /** Restores explicit search criteria so current saved preferences can be applied again. */
  public static ConversationDraft restoreSearchCriteria(
      ConversationTurn pending, SessionRequest reply) {
    var resumed = restoreSearchCriteria(pending);
    resumed.setRequestId(reply.getRequestId());
    resumed.setRawText(reply.getRawText());
    resumed.setCreatedAt(reply.getCreatedAt());
    return resumed;
  }

  /** Restores explicit search criteria so current saved preferences can be applied again. */
  public static ConversationDraft restoreSearchCriteria(ConversationTurn pending) {
    var resumed = ConversationDraft.fromTurn(pending);
    resumed.setPendingQuestion(null);
    // Keep explicit constraints; the search handler reapplies current preferences after
    // confirmation.
    if (resumed.getSearchCriteria() != null) {
      var explicit = resumed.getSearchCriteria().copy();
      resumed.setPositiveSemanticQuery(explicit.getPositiveSemanticQuery());
      resumed.setNegativeSemanticQuery(explicit.getNegativeSemanticQuery());
      resumed.setMustInclude(explicit.getMustInclude());
      resumed.setMustNotInclude(explicit.getMustNotInclude());
    }
    resumed.setUserPreferenceSnapshot(null);
    resumed.setUserPreferencesLoaded(false);
    return resumed;
  }
}
