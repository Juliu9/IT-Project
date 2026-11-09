package com.gen3.recommenderagent.application.conversation.search;

import static com.gen3.recommenderagent.application.conversation.search.SearchCriteriaMerger.copyOf;
import static com.gen3.recommenderagent.application.conversation.search.SearchCriteriaMerger.merge;
import static com.gen3.recommenderagent.domain.recommendation.Recommendation.bookIds;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.ConversationHistory;
import com.gen3.recommenderagent.application.conversation.ConversationRules;
import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.RefineHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.PreferenceRequestMapper;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.inputparser.ConversationText;
import com.gen3.recommenderagent.inputparser.UserIntent;
import java.util.List;

/** Owns search inheritance, frozen MORE context, current preferences and search completion. */
public abstract class SearchContextResolver implements ConversationHandler {
  /** Copies retained exact constraints into a repeated search clarification. */
  protected void attachPendingSearchCriteria(Session session, ConversationDraft request) {
    var pending = session.getPendingRefinementRequest();
    if (pending != null) {
      request.setMustInclude(ConversationHistory.restoreSearchCriteria(pending).getMustInclude());
    }
  }

  @Override
  public void validate(
      Session session,
      ConversationDraft result,
      SessionRequest original,
      com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy filters,
      com.gen3.recommenderagent.application.recommendation.SimilarBookResolver similarBooks,
      com.gen3.recommenderagent.application.conversation.HandlerRegistry handlers) {
    if (!result.isSearchClarificationRequired()) {
      boolean awaitingTaste = session != null && session.isAwaitingPersonalisedPreferences();
      var previousSearch =
          original != null
                  && !(awaitingTaste && original.startsNewSearch())
                  && (handlers.handler(original.getIntent()).inheritsSearch()
                      || (awaitingTaste && !original.startsNewSearch()))
              ? ConversationHistory.latestSearch(session)
              : null;
      SessionRequest previous =
          previousSearch != null
                  && (!handlers.handler(original.getIntent()).reusesSearchSnapshot()
                      || awaitingTaste)
                  && previousSearch.getSearchCriteria() != null
              ? PreferenceRequestMapper.toRequest(previousSearch.getSearchCriteria())
              : previousSearch;
      filters.resolveFilterCorrections(result, original, previous);
      if (result.getSearchCriteria() != null) {
        var criteria = PreferenceRequestMapper.toRequest(result.getSearchCriteria());
        filters.resolveFilterCorrections(criteria, original, previous);
        result.setSearchCriteria(PreferenceRequestMapper.fromRequest(criteria));
      }
    }
    similarBooks.resolve(session, result, clearsQuestionWithoutReference());
  }

  /** A new recommendation abandons a stale reference question when no reference is supplied. */
  protected boolean clearsQuestionWithoutReference() {
    return false;
  }

  /**
   * Resolves search criteria, saved preferences, clarification state, and the requested page count.
   */
  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (session == null) {
      return effective;
    }
    if (session.isAwaitingPersonalisedPreferences()
        && !(effective.isShorter() || effective.isLonger())
        && !reusesSearchSnapshot()) {
      if (!effective.startsNewSearch()) {
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
      effective.setIntent(RecommendationHandler.ACTION);
    }
    if (inheritsSearch() && intents().contains(effective.getIntent())) {
      applyPreviousSearch(effective, session);
    }
    if (isSearchAction()) {
      if (!reusesSearchSnapshot()) {
        effective.setSearchCriteria(PreferenceRequestMapper.fromRequest(effective));
        applyPreferences(effective, session);
      }
      effective.setBookCount(resolveBookCount(effective, session));
    }
    return effective;
  }

  /** Selects whether this search starts from the active criteria. */
  public boolean inheritsSearch() {
    return false;
  }

  /** Selects whether the active preferences and similarity vector stay frozen for another page. */
  public boolean reusesSearchSnapshot() {
    return false;
  }

  /** Selects whether this search accepts replacement criteria and personalisation wording. */
  protected boolean acceptsSearchEdits() {
    return true;
  }

  /** Inherits active-search context needed for refinements and follow-up pages. */
  private void applyPreviousSearch(ConversationDraft target, Session session) {
    ConversationTurn current = session.getActiveSearch().getSearch();
    if (current == null) {
      return;
    }
    ConversationDraft previous = ConversationDraft.fromTurn(current);
    {
      SessionPreferences moreCriteria = null;
      if (reusesSearchSnapshot() && previous.getSearchCriteria() != null) {
        var explicit = copyOf(target);
        merge(PreferenceRequestMapper.toRequest(previous.getSearchCriteria()), explicit);
        moreCriteria = PreferenceRequestMapper.fromRequest(explicit);
      }
      if (!reusesSearchSnapshot() && previous.getSearchCriteria() != null) {
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
        if (reusesSearchSnapshot()) {
          target
              .getSearchContext()
              .setSimilarityQueryVector(previous.getSearchContext().getSimilarityQueryVector());
        }
      }
      if (reusesSearchSnapshot()) {
        target.setSearchCriteria(moreCriteria);
        target.setUserPreferenceSnapshot(previous.getUserPreferenceSnapshot());
        target.setUserPreferencesLoaded(previous.isUserPreferencesLoaded());
      }
      return;
    }
  }

  /** Combines explicit search criteria with applicable saved session preferences. */
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

  /** Applies the saved count when the current search does not supply a valid override. */
  protected Integer resolveBookCount(ConversationDraft request, Session session) {
    if (request != null
        && !request.isInvalidBookCount()
        && request.getBookCount() != null
        && request.getBookCount() > 0) {
      return request.getBookCount();
    }
    return session == null ? null : session.getBookCount();
  }

  /** Reports whether the resolved action requires a recommendation page. */
  public boolean needsRecommendations(ConversationDraft request) {
    return !request.isSearchClarificationRequired();
  }

  /** Reports whether this handler retains reusable search context after completion. */
  public boolean retainsHistory() {
    return true;
  }

  /** Retains the completed search page and records the books shown to the user. */
  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    if (request.isSearchClarificationRequired()) {
      request.setRecommendations(List.of());
      return;
    }
    if (acceptsSearchEdits()
        && original != null
        && !original.startsNewSearch()
        && request.isPreferenceAnswer()
        && !request.isPreferenceClarificationRequired()) {
      var taste = new SessionPreferences();
      taste.setPositiveSemanticQuery(original.getPositiveSemanticQuery());
      taste.setNegativeSemanticQuery(original.getNegativeSemanticQuery());
      session.setPreferences(session.updatedPreferences(taste, null, List.of()));
    }
    request.setNoMoreRecommendations(
        reusesSearchSnapshot() && request.getRecommendations().isEmpty());
    session.getActiveSearch().addShownBookIds(bookIds(request.getRecommendations()));
  }

  @Override
  public void prepare(String rawText, UserIntent parsed, SessionRequest request) {
    request.setStartsNewSearch(startsNewSearch(rawText, parsed));
    applySearchReplacements(rawText, parsed, request);
    applyPersonalisation(rawText, request);
  }

  /** Checks whether a validated search-field path has a supplied value before replacing it. */
  private boolean hasSearchValue(UserIntent parsed, String path) {
    String[] parts = path.split("\\.");
    Object value;
    if (parts[0].equals("mustInclude") || parts[0].equals("mustNotInclude")) {
      var criteria =
          parts[0].equals("mustInclude") ? parsed.getMustInclude() : parsed.getMustNotInclude();
      if (criteria == null) {
        return false;
      }
      value =
          switch (parts[1]) {
            case "authors" -> criteria.getAuthors();
            case "narrators" -> criteria.getNarrators();
            case "duration" -> criteria.getDuration();
            case "publicationYear" -> criteria.getPublicationYear();
            case "language" -> criteria.getLanguage();
            case "source" -> criteria.getSource();
            default -> null;
          };
    } else {
      var query =
          parts[0].equals("positiveSemanticQuery")
              ? parsed.getPositiveSemanticQuery()
              : parsed.getNegativeSemanticQuery();
      if (query == null) {
        return false;
      }
      value =
          switch (parts[1]) {
            case "authors" -> query.getAuthors();
            case "narrators" -> query.getNarrators();
            case "genres" -> query.getGenres();
            case "topics" -> query.getTopics();
            default -> null;
          };
    }
    return value instanceof List<?> list
        ? !list.isEmpty()
        : value instanceof String text && !text.isBlank();
  }

  /** Conversational precedence is application policy, not model output. */
  private boolean startsNewSearch(String rawText, UserIntent parsed) {
    if (!acceptsSearchEdits()) {
      return false;
    }
    String command = ConversationText.normalizeCommand(rawText);
    return command.matches(
            "^(?:(?:recommend|suggest|find|show me|give me|search for|look for|help me find)\\b.*"
                + "|(?:i want|i'd like|i would like|i need|i'm looking for)\\b.*"
                + "|(?:only )?(?:books?|audiobooks?|recommendations?)\\b.*"
                + "|the same but\\b.*)$")
        || parsed.isShorter()
        || parsed.isLonger()
        || !parsed.getReplaceSearchFields().isEmpty()
        || ConversationText.isYearRefinement(
            parsed.getMustInclude() == null ? null : parsed.getMustInclude().getPublicationYear())
        || parsed.getSimilarToTitle() != null
        || parsed.isSimilarToClarificationRequired();
  }

  /**
   * Validates search replacements and preserves supplied additions unless replacement is explicit.
   */
  private void applySearchReplacements(String rawText, UserIntent parsed, SessionRequest request) {
    if (acceptsSearchEdits()) {
      new SessionPreferences().updated(null, null, parsed.getReplaceSearchFields());
      request.setReplaceSearchFields(parsed.getReplaceSearchFields());
      String command =
          com.gen3.recommenderagent.inputparser.ConversationText.normalizeCommand(rawText);
      if (command.matches("^(?:also|add|additionally|(?:make (?:it|them) )?more)\\b.*")
          && !command.matches(".*\\b(?:instead|replace|rather than|switch)\\b.*")) {
        // Keep field removals while protecting supplied additions from model replacement flags.
        var search = parsed;
        request.setReplaceSearchFields(
            parsed.getReplaceSearchFields().stream()
                .filter(path -> !hasSearchValue(search, path))
                .toList());
      }
    }
  }

  /**
   * Applies explicit reading-history references and direct person-search wording to
   * personalisation.
   */
  private void applyPersonalisation(String rawText, SessionRequest request) {
    String command =
        com.gen3.recommenderagent.inputparser.ConversationText.normalizeCommand(rawText);
    if (acceptsSearchEdits()
        && command.matches(
            ".*\\b(?:my preferences|my reading|my favourites|my favorites|my history|what i (?:liked|enjoyed|read|listened to) (?:before|previously))\\b.*")) {
      request.setPersonalised(true);
      return;
    }
    var query = request.getPositiveSemanticQuery();
    boolean personStyle =
        query != null
            && (ConversationRules.hasTerms(query.getAuthors())
                || ConversationRules.hasTerms(query.getNarrators()))
            && command.matches(".*\\bsimilar to\\b.*\\bstyle\\b.*");
    boolean directPersonSearch =
        command.matches(
            "^(?:i want |give me |recommend |show me )?(?:a |an |some |[0-9]+ )?(?:books?|audiobooks?) (?:by|narrated by) .+");
    if ((directPersonSearch || personStyle)
        && !command.matches(
            ".*\\b(?:for me|personalised|personalized|my preferences|my reading|my favourites|my favorites|my history|i would like|i'd like)\\b.*")) {
      request.setPersonalised(false);
    }
  }

  @Override
  public void finishQuestion(ConversationDraft request) {
    ConversationHandler.super.finishQuestion(request);
    if (request.getOutcome() == null && request.getPendingQuestion() == null) {
      if (request.isSearchClarificationRequired()) {
        request.setPendingQuestion(RecommendationHandler.SEARCH_CLARIFICATION);
      } else if (request.isPreferenceClarificationRequired()) {
        request.setPendingQuestion(RecommendationHandler.PREFERENCE_CLARIFICATION);
      }
    }
    if (request.getPendingQuestion() == null
        && request.getRecommendations() != null
        && request.getRecommendations().isEmpty()) {
      request.setPendingQuestion(RecommendationHandler.SEARCH_CHANGE_OFFER);
    }
  }

  @Override
  public void normalizeFields(SessionRequest request) {
    if (!acceptsSearchEdits()) {
      return;
    }
    if (!request.getReplaceSearchFields().isEmpty()
        || request.isShorter()
        || request.isLonger()
        || (request.getMustInclude() != null
            && ConversationText.isYearRefinement(request.getMustInclude().getPublicationYear()))) {
      request.setIntent(RefineHandler.ACTION);
      request.setStartsNewSearch(true);
    } else if (ConversationRules.hasText(request.getSimilarToTitle())
        || request.isSimilarToClarificationRequired()) {
      request.setStartsNewSearch(true);
    }
  }

  /** Restores a retained search after its owning handler accepts a clarification reply. */
  protected void resumeSearchReply(
      Session session,
      ConversationDraft effective,
      ConversationQuestion question,
      ConversationAction action) {
    if (session.getPendingQuestionKind() == question
        && session.getPendingRefinementRequest() != null
        && !effective.startsNewSearch()
        && (effective.getIntent() == RecommendationHandler.ACTION
            || effective.getIntent() == RefineHandler.ACTION)) {
      var pending = session.getPendingRefinementRequest();
      effective.setIntent(action);
      if (effective.getBookCount() == null) {
        effective.setBookCount(pending.getBookCount());
      }
      merge(ConversationHistory.restoreSearchCriteria(pending), effective);
      session.setPendingRefinementRequest(null);
      session.setPendingQuestionKind(null);
    }
  }

  @Override
  public String fallbackResponse(
      com.gen3.recommenderagent.domain.conversation.ConversationTurn turn, int labelWords) {
    if (turn.isPersonalised()) {
      return RecommendationHandler.personalisedNoMatches();
    }
    if (turn.getMustInclude() != null && turn.getMustInclude().getPublicationYear() != null) {
      return "I couldn't find books matching those criteria. Would you like to try a different author, genre, or filter?";
    }
    if (turn.getRawText() != null && !turn.getRawText().isBlank()) {
      return "I couldn't find a good match for "
          + com.gen3.recommenderagent.response.ResponseBudget.label(turn.getRawText(), labelWords)
          + " just yet. Would you like to try a different genre, author, narrator, or description?";
    }
    return null;
  }
}
