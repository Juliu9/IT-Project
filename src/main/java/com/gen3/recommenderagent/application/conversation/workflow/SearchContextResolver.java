package com.gen3.recommenderagent.application.conversation.workflow;

import static com.gen3.recommenderagent.application.conversation.workflow.ConversationHistory.latestSearch;
import static com.gen3.recommenderagent.application.conversation.workflow.ConversationRules.hasSearchCriteria;
import static com.gen3.recommenderagent.application.conversation.workflow.ConversationRules.isRecommendationIntent;
import static com.gen3.recommenderagent.application.conversation.workflow.ConversationRules.isSearchContext;
import static com.gen3.recommenderagent.application.conversation.workflow.SearchCriteriaMerger.copyOf;
import static com.gen3.recommenderagent.application.conversation.workflow.SearchCriteriaMerger.merge;
import static com.gen3.recommenderagent.domain.recommendation.Recommendation.bookIds;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.PreferenceRequestMapper;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.inputparser.ConversationText;
import java.util.List;

/** Owns search inheritance, frozen MORE context, current preferences and search completion. */
public final class SearchContextResolver implements ConversationHandler {
  public java.util.Set<ConversationAction> intents() {
    return java.util.Set.of(
        ConversationAction.RECOMMENDATION,
        ConversationAction.REFINE,
        ConversationAction.MORE_RESULTS);
  }

  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (effective.getIntent() == ConversationAction.RECOMMENDATION
        && !hasSearchCriteria(effective)) {
      effective.setPersonalised(true);
    }
    if (session == null) {
      return effective;
    }
    // Check before preference handling: MORE cannot invent a first search.
    if (effective.getIntent() == ConversationAction.MORE_RESULTS
        && (session.getActiveSearch().getSearch() == null
            || !isRecommendationIntent(session.getActiveSearch().getSearch().getIntent()))) {
      effective.setBookCount(resolveBookCount(effective, session));
      effective.setSearchClarificationRequired(true);
      session.setPendingRefinementRequest(ConversationDraft.toStoredTurn(effective));
      return effective;
    }
    if (effective.getIntent() == ConversationAction.REFINE
        && latestSearch(session) == null
        && !session.isAwaitingPersonalisedPreferences()) {
      if ((effective.isShorter() || effective.isLonger())
          || ConversationText.isYearRefinement(effective.getRawText())
          || (effective.getMustInclude() != null
              && ConversationText.isYearRefinement(effective.getMustInclude().getPublicationYear()))
          || !hasSearchCriteria(effective)) {
        effective.setSearchClarificationRequired(true);
        return effective;
      }
      effective.setIntent(ConversationAction.RECOMMENDATION);
    }
    if (session.isAwaitingPersonalisedPreferences()
        && !(effective.isShorter() || effective.isLonger())
        && effective.getIntent() != ConversationAction.MORE_RESULTS
        && acceptsPreferences(effective.getIntent())) {
      if (!effective.isExplicitSearch()) {
        effective.setPreferenceAnswer(
            ConversationRules.hasQuery(effective.getPositiveSemanticQuery())
                || ConversationRules.hasQuery(effective.getNegativeSemanticQuery()));
        var pendingTurn = session.getPendingPreferenceRequest();
        var pending = pendingTurn == null ? null : ConversationDraft.fromTurn(pendingTurn);
        if (pending != null) {
          merge(pending, effective);
          if (effective.getBookCount() == null) {
            effective.setBookCount(pending.getBookCount());
          }
        }
        effective.setPersonalised(true);
      }
      effective.setIntent(ConversationAction.RECOMMENDATION);
    }
    if (effective.getIntent() == ConversationAction.REFINE
        || effective.getIntent() == ConversationAction.MORE_RESULTS) {
      applyPreviousSearch(effective, session);
    }
    if (isRecommendationIntent(effective.getIntent())) {
      if (effective.getIntent() != ConversationAction.MORE_RESULTS) {
        effective.setSearchCriteria(PreferenceRequestMapper.fromRequest(effective));
        applyPreferences(effective, session);
      }
      effective.setBookCount(resolveBookCount(effective, session));
    }
    return effective;
  }

  private boolean acceptsPreferences(ConversationAction intent) {
    return intent == ConversationAction.UPDATE_PREFERENCES
        || intent == ConversationAction.RECOMMENDATION
        || intent == ConversationAction.REFINE
        || intent == ConversationAction.MORE_RESULTS;
  }

  private void applyPreviousSearch(ConversationDraft target, Session session) {
    ConversationTurn current = session.getActiveSearch().getSearch();
    if (current == null || !isSearchContext(current)) {
      return;
    }
    ConversationDraft previous = ConversationDraft.fromTurn(current);
    {
      SessionPreferences moreCriteria = null;
      if (target.getIntent() == ConversationAction.MORE_RESULTS
          && previous.getSearchCriteria() != null) {
        var explicit = copyOf(target);
        merge(PreferenceRequestMapper.toRequest(previous.getSearchCriteria()), explicit);
        moreCriteria = PreferenceRequestMapper.fromRequest(explicit);
      }
      if (target.getIntent() == ConversationAction.REFINE && previous.getSearchCriteria() != null) {
        merge(PreferenceRequestMapper.toRequest(previous.getSearchCriteria()), target);
        target.setPersonalised(previous.isPersonalised() || target.isPersonalised());
      } else {
        merge(previous, target);
      }
      var replacements = new java.util.LinkedHashSet<>(previous.getReplaceSearchFields());
      replacements.addAll(target.getReplaceSearchFields());
      target.setReplaceSearchFields(new java.util.ArrayList<>(replacements));
      if (target.getSimilarToTitle() == null && !target.isSimilarToClarificationRequired()) {
        target.getSearchContext().setSimilarBook(previous.getSearchContext().getSimilarBook());
        if (target.getIntent() == ConversationAction.MORE_RESULTS) {
          target
              .getSearchContext()
              .setSimilarityQueryVector(previous.getSearchContext().getSimilarityQueryVector());
        }
      }
      if (target.getIntent() == ConversationAction.MORE_RESULTS) {
        target.setSearchCriteria(moreCriteria);
        target.setUserPreferenceSnapshot(previous.getUserPreferenceSnapshot());
        target.setUserPreferencesLoaded(previous.isUserPreferencesLoaded());
      }
      return;
    }
  }

  private void applyPreferences(ConversationDraft target, Session session) {
    if (target == null || session == null || session.getPreferences() == null) {
      return;
    }
    SessionPreferences preferences = session.getPreferences();
    ConversationDraft base = new ConversationDraft();
    if (target.isPersonalised()
        || (target.getSimilarToTitle() == null
            && target.getSearchContext().getSimilarBook() == null)) {
      base.setPositiveSemanticQuery(preferences.getPositiveSemanticQuery());
      base.setNegativeSemanticQuery(preferences.getNegativeSemanticQuery());
    }
    base.setMustInclude(preferences.getMustInclude());
    base.setMustNotInclude(preferences.getMustNotInclude());
    merge(base, target);
  }

  private Integer resolveBookCount(ConversationDraft request, Session session) {
    if (request != null
        && !request.isInvalidBookCount()
        && request.getBookCount() != null
        && request.getBookCount() > 0) {
      return request.getBookCount();
    }
    return session == null ? null : session.getBookCount();
  }

  public boolean invalidatesBatch(SessionRequest request) {
    ConversationAction intent = request == null ? ConversationAction.UNKNOWN : request.getIntent();
    ConversationDraft filters = new ConversationDraft();
    if (request != null) {
      filters.setMustInclude(request.getMustInclude());
      filters.setMustNotInclude(request.getMustNotInclude());
    }
    return intent == ConversationAction.RECOMMENDATION
        || intent == ConversationAction.REFINE
        || intent == ConversationAction.CLEAR_HISTORY
        || (intent == ConversationAction.MORE_RESULTS && hasSearchCriteria(filters));
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
    ConversationAction originalIntent =
        original == null ? ConversationAction.UNKNOWN : original.getIntent();
    if ((originalIntent == ConversationAction.REFINE
            || originalIntent == ConversationAction.RECOMMENDATION)
        && !original.isExplicitSearch()
        && request.isPreferenceAnswer()
        && !request.isPreferenceClarificationRequired()) {
      var taste = new SessionPreferences();
      taste.setPositiveSemanticQuery(original.getPositiveSemanticQuery());
      taste.setNegativeSemanticQuery(original.getNegativeSemanticQuery());
      session.setPreferences(session.updatedPreferences(taste, null, List.of()));
    }
    request.setNoMoreRecommendations(
        request.getIntent() == ConversationAction.MORE_RESULTS
            && request.getRecommendations().isEmpty());
    session.getActiveSearch().addShownBookIds(bookIds(request.getRecommendations()));
  }
}
