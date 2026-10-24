package com.gen3.recommenderagent.domain.session;

import static com.gen3.recommenderagent.domain.session.ConversationRules.isSearchContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Retains reusable searches and completed replay snapshots independently of intent execution. */
public final class ConversationHistory {
  static boolean awaitingPreferences(Session session) {
    return session.getRequests() != null
        && session.getRequests().stream()
            .anyMatch(ConversationTurn::isPreferenceClarificationRequired);
  }

  static void retainRequest(Session session, ConversationTurn request) {
    if (request == null) {
      return;
    }
    // Keep one search and one non-search entry so actions do not erase follow-up context.
    boolean search = isSearchContext(request);
    session.getRequests().removeIf(existing -> isSearchContext(existing) == search);
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

  static List<String> recommendationIds(List<Recommendation> recommendations) {
    return recommendations.stream()
        .filter(recommendation -> recommendation != null)
        .map(Recommendation::getBookId)
        .filter(bookId -> bookId != null && !bookId.isBlank())
        .toList();
  }

  // Persist only reusable context; raw text and the temporary decision stay out of history.
  static ConversationTurn historyEntry(ConversationDraft request) {
    ConversationDraft entry = new ConversationDraft();
    entry.setRequestId(request.getRequestId());
    entry.setCreatedAt(request.getCreatedAt());
    entry.setIntent(request.getIntent());
    entry.setSearchCriteria(
        request.getSearchCriteria() == null ? null : request.getSearchCriteria().copy());
    entry.setUserPreferenceSnapshot(request.getUserPreferenceSnapshot());
    entry.setUserPreferencesLoaded(request.isUserPreferencesLoaded());
    entry.setPersonalised(request.isPersonalised());
    entry.setPreferenceClarificationRequired(request.isPreferenceClarificationRequired());
    entry.setPositiveSemanticQuery(request.getPositiveSemanticQuery());
    entry.setNegativeSemanticQuery(request.getNegativeSemanticQuery());
    entry.setMustInclude(request.getMustInclude());
    entry.setMustNotInclude(request.getMustNotInclude());
    entry.setBookCount(request.getBookCount());
    entry.setRecommendations(request.getRecommendations());
    entry.setNoMoreRecommendations(request.isNoMoreRecommendations());
    entry.setSearchClarificationRequired(request.isSearchClarificationRequired());
    entry.setReferenceRank(request.getReferenceRank());
    entry.setReferenceTitle(request.getReferenceTitle());
    return ConversationTurn.fromDraft(entry).snapshot();
  }

  public static ConversationDraft latestSearch(Session session) {
    return session.getRequests() == null
        ? null
        : session.getRequests().stream()
            .filter(ConversationRules::isSearchContext)
            .reduce((first, second) -> second)
            .map(ConversationTurn::toDraft)
            .orElse(null);
  }

  static void remember(Session session, ConversationDraft request) {
    session.setLastRequest(historyEntry(request));
    session.setLastDecision(request.getDecision());
  }
}
