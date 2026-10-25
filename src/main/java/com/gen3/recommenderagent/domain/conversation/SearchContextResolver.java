package com.gen3.recommenderagent.domain.conversation;

import static com.gen3.recommenderagent.domain.conversation.ConversationHistory.latestSearch;
import static com.gen3.recommenderagent.domain.conversation.ConversationRules.hasSearchCriteria;
import static com.gen3.recommenderagent.domain.conversation.ConversationRules.isRecommendationIntent;
import static com.gen3.recommenderagent.domain.conversation.ConversationRules.isSearchContext;
import static com.gen3.recommenderagent.domain.conversation.SearchCriteriaMerger.copyOf;
import static com.gen3.recommenderagent.domain.conversation.SearchCriteriaMerger.merge;
import static com.gen3.recommenderagent.domain.session.Recommendation.bookIds;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;

/** Owns search inheritance, frozen MORE context, current preferences and search completion. */
public final class SearchContextResolver implements ConversationHandler {
  public java.util.Set<Intent> intents() {
    return java.util.Set.of(Intent.RECOMMENDATION, Intent.REFINE, Intent.MORE_RESULTS);
  }

  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (effective.getIntent() == Intent.RECOMMENDATION && !hasSearchCriteria(effective)) {
      effective.setPersonalised(true);
    }
    if (session == null) {
      return effective;
    }
    // Check before preference handling: MORE cannot invent a first search.
    if (effective.getIntent() == Intent.MORE_RESULTS
        && (session.getRequests() == null
            || session.getRequests().stream()
                .noneMatch(
                    previous ->
                        previous != null && isRecommendationIntent(previous.getIntent())))) {
      effective.setSearchClarificationRequired(true);
      return effective;
    }
    if (effective.getIntent() == Intent.REFINE
        && latestSearch(session) == null
        && !session.isAwaitingPersonalisedPreferences()) {
      if (!hasSearchCriteria(effective)) {
        effective.setSearchClarificationRequired(true);
        return effective;
      }
      effective.setIntent(Intent.RECOMMENDATION);
    }
    if (session.isAwaitingPersonalisedPreferences()
        && effective.getIntent() != Intent.MORE_RESULTS
        && acceptsPreferences(effective.getIntent())) {
      if (!effective.isExplicitSearch()) {
        var pending = latestSearch(session);
        if (pending != null) {
          merge(pending, effective);
          if (effective.getBookCount() == null) {
            effective.setBookCount(pending.getBookCount());
          }
        }
        effective.setPersonalised(true);
      }
      effective.setIntent(Intent.RECOMMENDATION);
    }
    if (effective.getIntent() == Intent.REFINE || effective.getIntent() == Intent.MORE_RESULTS) {
      applyPreviousSearch(effective, session);
    }
    if (isRecommendationIntent(effective.getIntent())) {
      if (effective.getIntent() != Intent.MORE_RESULTS) {
        effective.setSearchCriteria(SessionPreferences.fromRequest(effective));
        applyPreferences(effective, session);
      }
      effective.setBookCount(resolveBookCount(effective, session));
    }
    return effective;
  }

  private boolean acceptsPreferences(Intent intent) {
    return intent == Intent.UPDATE_PREFERENCES
        || intent == Intent.RECOMMENDATION
        || intent == Intent.REFINE
        || intent == Intent.MORE_RESULTS;
  }

  private void applyPreviousSearch(ConversationDraft target, Session session) {
    List<ConversationTurn> requests = session.getRequests();
    if (requests == null) {
      return;
    }
    for (int index = requests.size() - 1; index >= 0; index--) {
      ConversationDraft previous = requests.get(index).toDraft();
      if (isSearchContext(previous)) {
        SessionPreferences moreCriteria = null;
        if (target.getIntent() == Intent.MORE_RESULTS && previous.getSearchCriteria() != null) {
          var explicit = copyOf(target);
          merge(previous.getSearchCriteria().asRequest(), explicit);
          moreCriteria = SessionPreferences.fromRequest(explicit);
        }
        if (target.getIntent() == Intent.REFINE && previous.getSearchCriteria() != null) {
          merge(previous.getSearchCriteria().asRequest(), target);
          target.setPersonalised(previous.isPersonalised() || target.isPersonalised());
        } else {
          merge(previous, target);
        }
        if (target.getIntent() == Intent.MORE_RESULTS) {
          target.setSearchCriteria(moreCriteria);
          target.setUserPreferenceSnapshot(previous.getUserPreferenceSnapshot());
          target.setUserPreferencesLoaded(previous.isUserPreferencesLoaded());
        }
        return;
      }
    }
  }

  private void applyPreferences(ConversationDraft target, Session session) {
    if (target == null || session == null || session.getPreferences() == null) {
      return;
    }
    SessionPreferences preferences = session.getPreferences();
    ConversationDraft base = new ConversationDraft();
    base.setPositiveSemanticQuery(preferences.getPositiveSemanticQuery());
    base.setNegativeSemanticQuery(preferences.getNegativeSemanticQuery());
    base.setMustInclude(preferences.getMustInclude());
    base.setMustNotInclude(preferences.getMustNotInclude());
    merge(base, target);
  }

  private Integer resolveBookCount(ConversationDraft request, Session session) {
    if (request != null && request.getBookCount() != null) {
      return request.getBookCount();
    }
    return session == null ? null : session.getBookCount();
  }

  public boolean invalidatesBatch(SessionRequest request) {
    Intent intent = request == null ? Intent.UNKNOWN : request.getIntent();
    ConversationDraft filters = new ConversationDraft();
    if (request != null) {
      filters.setMustInclude(request.getMustInclude());
      filters.setMustNotInclude(request.getMustNotInclude());
    }
    return intent == Intent.RECOMMENDATION
        || intent == Intent.REFINE
        || intent == Intent.CLEAR_HISTORY
        || (intent == Intent.MORE_RESULTS && hasSearchCriteria(filters));
  }

  public boolean needsRecommendations(ConversationDraft request) {
    return !request.isSearchClarificationRequired();
  }

  public boolean retainsHistory() {
    return true;
  }

  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    if (request.isSearchClarificationRequired()) {
      request.setRecommendations(List.of());
      return;
    }
    Intent originalIntent = original == null ? Intent.UNKNOWN : original.getIntent();
    if ((originalIntent == Intent.REFINE || originalIntent == Intent.RECOMMENDATION)
        && session.isAwaitingPersonalisedPreferences()
        && !original.isExplicitSearch()
        && !request.isPreferenceClarificationRequired()) {
      session.setPreferences(SessionPreferences.fromRequest(request));
    }
    session.setAwaitingPreferenceUpdate(false);
    request.setNoMoreRecommendations(
        request.getIntent() == Intent.MORE_RESULTS && request.getRecommendations().isEmpty());
    session.addRecommendationIds(bookIds(request.getRecommendations()));
  }
}
