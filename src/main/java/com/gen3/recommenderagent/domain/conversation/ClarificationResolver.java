package com.gen3.recommenderagent.domain.conversation;

import static com.gen3.recommenderagent.domain.conversation.ConversationRules.normalizeInput;
import static com.gen3.recommenderagent.domain.conversation.SearchCriteriaMerger.copyOf;

import com.gen3.catalogue.model.AudiobookTitle;
import com.gen3.catalogue.model.PersonNameNormalizer;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.domain.session.SimilarBookReference;
import java.util.List;

/**
 * Resolves short replies and cancellation against the outstanding question before intent dispatch.
 */
public final class ClarificationResolver {

  public ConversationDraft resolve(Session session, SessionRequest input) {
    ConversationDraft effective = copyOf(input);
    if (session == null) {
      return effective;
    }
    if (session.getPendingQuestion() == null) {
      if (isQuestion(session.getLastDecision())) {
        session.setPendingQuestion(session.getLastDecision());
      } else if (session.isAwaitingPersonalisedPreferences()) {
        session.setPendingQuestion(ConversationDecision.PREFERENCE_CLARIFICATION);
      } else if (session.isAwaitingPreferenceUpdate()) {
        session.setPendingQuestion(ConversationDecision.PREFERENCE_UPDATE_CLARIFICATION);
      }
    }
    String reply = effective.getRawText() == null ? "" : effective.getRawText().strip();
    if (reply.matches("(?i)(?:never mind|nevermind|cancel|stop|forget it)[.!?]?")) {
      effective.setIntent(Intent.CANCEL);
    }
    if ((session.getPendingQuestion() == ConversationDecision.PREFERENCE_CLARIFICATION
            || session.getPendingQuestion() == ConversationDecision.PREFERENCE_UPDATE_CLARIFICATION)
        && ConversationText.isPreferenceDecline(reply)) {
      effective.setIntent(Intent.CANCEL);
    }
    if (effective.getIntent() == Intent.CANCEL) {
      normalizeInput(effective);
      return effective;
    }
    if (effective.getIntent() == Intent.UPDATE_PREFERENCES
        || (session.isAwaitingPreferenceUpdate()
            && session.getPendingQuestion() != ConversationDecision.EXPLANATION_TARGET_CLARIFICATION
            && session.getPendingQuestion() != ConversationDecision.COUNT_CLARIFICATION
            && session.getPendingQuestion() != ConversationDecision.SHORTER_DURATION_CLARIFICATION
            && session.getPendingQuestion() != ConversationDecision.RECENCY_YEAR_CLARIFICATION
            && session.getPendingQuestion() != ConversationDecision.RECENCY_RANGE_CONFIRMATION
            && !effective.isExplicitSearch()
            && (effective.getIntent() == Intent.RECOMMENDATION
                || effective.getIntent() == Intent.REFINE
                || effective.getIntent() == Intent.UNKNOWN))) {
      effective.setIntent(Intent.UPDATE_PREFERENCES);
      if (effective.getPreferenceUpdates() == null) {
        effective.setPreferenceUpdates(SessionPreferences.fromRequest(effective));
      }
    }
    if (effective.getIntent() == Intent.UPDATE_PREFERENCES
        && session.isAwaitingPersonalisedPreferences()
        && effective.getPreferenceUpdates() != null
        && (ConversationRules.hasQuery(effective.getPreferenceUpdates().getPositiveSemanticQuery())
            || ConversationRules.hasQuery(
                effective.getPreferenceUpdates().getNegativeSemanticQuery()))) {
      var updates = effective.getPreferenceUpdates();
      session.setPreferences(
          session.updatedPreferences(
              updates, effective.getPreferenceRemovals(), effective.getReplacePreferenceFields()));
      effective.setIntent(Intent.RECOMMENDATION);
      effective.setExplicitSearch(false);
      effective.setPositiveSemanticQuery(updates.getPositiveSemanticQuery());
      effective.setNegativeSemanticQuery(updates.getNegativeSemanticQuery());
      return effective;
    }
    if (session.getPendingQuestion() == ConversationDecision.RECENCY_RANGE_CONFIRMATION
        && session.getPendingRecencyRequest() != null) {
      String answer = ConversationText.normalizeCommand(reply);
      if (java.util.Set.of("no", "no thanks", "not now", "keep the previous search")
          .contains(answer)) {
        effective.setIntent(Intent.CANCEL);
        normalizeInput(effective);
        return effective;
      }
      if (ConversationText.isAffirmativeReply(reply)) {
        var resumed = restoreSearchCriteria(session.getPendingRecencyRequest());
        resumed.setRequestId(effective.getRequestId());
        resumed.setRawText(effective.getRawText());
        resumed.setCreatedAt(effective.getCreatedAt());
        resumed.setSearchClarificationRequired(false);
        resumed.setDecision(null);
        session.setPendingQuestion(null);
        session.setPendingRecencyRequest(null);
        return resumed;
      }
    }
    // The parser has no session context; resolve short answers against the outstanding question.
    if (session.getPendingQuestion() == ConversationDecision.EXPLANATION_TARGET_CLARIFICATION
        && ConversationText.isWholePageReply(reply)) {
      effective.setIntent(Intent.EXPLAIN_RECOMMENDATION);
      normalizeInput(effective);
      effective.setReferenceRank(null);
      effective.setReferenceTitle(null);
      return effective;
    }
    Integer selection = ConversationText.selectionReply(reply);
    if (session.getPendingQuestion() == ConversationDecision.EXPLANATION_TARGET_CLARIFICATION) {
      var search = ConversationHistory.latestSearch(session);
      var page =
          search == null || search.getRecommendations() == null
              ? List.<Recommendation>of()
              : search.getRecommendations();
      Integer relative = ConversationText.relativeSelectionReply(reply, page.size());
      if (relative != null) {
        selection =
            page.stream()
                .sorted(java.util.Comparator.comparing(Recommendation::getRank))
                .toList()
                .get(relative - 1)
                .getRank();
      } else if (selection != null
          && !page.stream().map(Recommendation::getRank).toList().contains(selection)
          && ExplanationConversationHandler.matchesDisplayedTitle(session, reply)) {
        selection = null;
      }
    }
    String yearReply = ConversationText.normalizeCommand(reply);
    if ((session.getPendingQuestion() == ConversationDecision.RECENCY_YEAR_CLARIFICATION
            || session.getPendingQuestion() == ConversationDecision.RECENCY_RANGE_CONFIRMATION)
        && !effective.isExplicitSearch()
        && (effective.getIntent() == Intent.UNKNOWN
            || effective.getIntent() == Intent.RECOMMENDATION
            || effective.getIntent() == Intent.REFINE)
        && (yearReply.matches("[0-9]{4}")
            || (effective.getMustInclude() != null
                && effective.getMustInclude().getPublicationYear() != null))) {
      effective.setIntent(Intent.REFINE);
      normalizeInput(effective);
      var include =
          effective.getMustInclude() == null
              ? new com.gen3.recommenderagent.domain.session.ExactCriteria()
              : effective.getMustInclude();
      if (yearReply.matches("[0-9]{4}")) {
        include.setPublicationYear("from " + yearReply);
      } else if (include.getPublicationYear().matches("[0-9]{4}")) {
        include.setPublicationYear("from " + include.getPublicationYear());
      }
      effective.setMustInclude(include);
      if (session.getPendingQuestion() == ConversationDecision.RECENCY_RANGE_CONFIRMATION) {
        if (session.getPendingRecencyRequest() != null) {
          SearchCriteriaMerger.merge(
              restoreSearchCriteria(session.getPendingRecencyRequest()), effective);
        }
        session.setPendingQuestion(null);
        session.setPendingRecencyRequest(null);
      }
      return effective;
    }
    if (session.getPendingQuestion() == ConversationDecision.COUNT_CLARIFICATION
        && selection != null) {
      effective.setIntent(Intent.CHANGE_COUNT);
      normalizeInput(effective);
      effective.setBookCount(selection);
      return effective;
    }
    if (session.getPendingQuestion() == ConversationDecision.EXPLANATION_TARGET_CLARIFICATION
        && (selection != null
            || (!effective.isExplicitSearch()
                && !reply.isBlank()
                && (effective.getIntent() == Intent.UNKNOWN
                    || ((effective.getIntent() == Intent.RECOMMENDATION
                            || effective.getIntent() == Intent.REFINE)
                        && ExplanationConversationHandler.matchesDisplayedTitle(
                            session, reply)))))) {
      effective.setIntent(Intent.EXPLAIN_RECOMMENDATION);
      normalizeInput(effective);
      effective.setReferenceRank(selection);
      effective.setReferenceTitle(selection == null ? reply : null);
      return effective;
    }
    if (session.getPendingQuestion() == ConversationDecision.SHORTER_DURATION_CLARIFICATION
        && !effective.isExplicitSearch()
        && effective.getMustInclude() != null
        && effective.getMustInclude().getDuration() != null
        && (effective.getIntent() == Intent.RECOMMENDATION
            || effective.getIntent() == Intent.REFINE
            || effective.getIntent() == Intent.UNKNOWN)) {
      effective.setIntent(Intent.REFINE);
      normalizeInput(effective);
      return effective;
    }
    if (session.getPendingSimilarBook() != null
        && (session.getPendingQuestion() == null
            || session.getPendingQuestion() == session.getPendingSimilarBook().decision())) {
      var resolved = resolveSimilarBook(session, effective, reply, selection);
      if (resolved != effective) {
        return resolved;
      }
    }
    if (effective.getIntent() == Intent.UPDATE_PREFERENCES) {
      return effective;
    }
    boolean tasteReply =
        session.isAwaitingPersonalisedPreferences()
            && !effective.isExplicitSearch()
            && (ConversationRules.hasQuery(effective.getPositiveSemanticQuery())
                || ConversationRules.hasQuery(effective.getNegativeSemanticQuery()));
    boolean newSearch =
        (effective.getIntent() == Intent.RECOMMENDATION && effective.isExplicitSearch())
            || (effective.getIntent() == Intent.REFINE && !tasteReply);
    if (newSearch) {
      session.setPendingQuestion(null);
      session.setSuspendedQuestion(null);
      session.setPendingSimilarBook(null);
      session.clearPreferenceQuestions();
    } else if ((effective.getIntent() == Intent.MORE_RESULTS
            || effective.getIntent() == Intent.REPEAT_RECOMMENDATIONS)
        && session.getPendingQuestion() != ConversationDecision.PREFERENCE_CLARIFICATION) {
      session.setPendingQuestion(null);
      session.setSuspendedQuestion(null);
      session.setPendingSimilarBook(null);
    }
    return effective;
  }

  private ConversationDraft restoreSearchCriteria(ConversationTurn pending) {
    var resumed = pending.toDraft();
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

  private ConversationDraft resolveSimilarBook(
      Session session, ConversationDraft input, String reply, Integer selection) {
    var pending = session.getPendingSimilarBook();
    var choices = pending.request().getSimilarBookChoices();
    if ((!choices.isEmpty() && ConversationText.isChoiceRejection(reply))
        || (pending.decision() == ConversationDecision.SIMILAR_BOOK_UNAVAILABLE
            && ConversationText.isAffirmativeReply(reply))) {
      return anotherReference(pending.request(), input);
    }
    if (selection == null) {
      selection = ConversationText.relativeSelectionReply(reply, choices.size());
    }
    // A bare choice belongs to the pending question, regardless of model-inferred search flags.
    if (selection == null
        && (input.isExplicitSearch()
            || input.getSimilarToTitle() != null
            || input.isSimilarToClarificationRequired())) {
      return input;
    }
    if (selection == null
        && input.getIntent() != Intent.UNKNOWN
        && input.getIntent() != Intent.RECOMMENDATION
        && input.getIntent() != Intent.REFINE) {
      return input;
    }
    AudiobookTitle selected = null;
    if (selection != null && selection > 0 && selection <= choices.size()) {
      selected = choices.get(selection - 1);
    } else if (!reply.isBlank()) {
      var matches =
          choices.stream()
              .filter(
                  book ->
                      AudiobookTitle.normalize(book.title()).equals(AudiobookTitle.normalize(reply))
                          || book.authors().stream()
                              .anyMatch(
                                  author ->
                                      PersonNameNormalizer.comparisonKey(author)
                                          .equals(PersonNameNormalizer.comparisonKey(reply))))
              .toList();
      if (matches.size() == 1) {
        selected = matches.getFirst();
      }
    }
    if (selected == null) {
      if (!choices.isEmpty() && (selection != null || input.getIntent() == Intent.UNKNOWN)) {
        var again = restoreSearchCriteria(pending.request());
        again.setDecision(ConversationDecision.SIMILAR_BOOK_SELECTION);
        again.setSearchClarificationRequired(true);
        again.setSimilarBookChoices(choices);
        again.setRequestId(input.getRequestId());
        again.setRawText(input.getRawText());
        return again;
      }
      if (choices.isEmpty()
          && (pending.decision() == ConversationDecision.SIMILAR_BOOK_REQUIRED
              || pending.decision() == ConversationDecision.SIMILAR_BOOK_UNAVAILABLE)
          && input.getIntent() == Intent.UNKNOWN
          && !reply.isBlank()) {
        var named = restoreSearchCriteria(pending.request());
        named.setRequestId(input.getRequestId());
        named.setRawText(input.getRawText());
        named.setCreatedAt(input.getCreatedAt());
        named.getSearchContext().setSimilarBook(null);
        named.getSearchContext().setSimilarityQueryVector(null);
        named.setSimilarToAuthor(null);
        named.setSimilarToTitle(reply);
        named.setSimilarToClarificationRequired(false);
        named.setSearchClarificationRequired(false);
        named.setDecision(null);
        named.setExplicitSearch(true);
        return named;
      }
      if (choices.isEmpty()
          && pending.decision() == ConversationDecision.SIMILAR_BOOK_NOT_FOUND
          && (input.getIntent() == Intent.RECOMMENDATION || input.getIntent() == Intent.REFINE)) {
        var tastes = SearchCriteriaMerger.copyOf(input);
        SearchCriteriaMerger.merge(restoreSearchCriteria(pending.request()), tastes);
        tastes.setIntent(Intent.RECOMMENDATION);
        tastes.setExplicitSearch(true);
        session.setPendingSimilarBook(null);
        return tastes;
      }
      return input;
    }
    var resumed = restoreSearchCriteria(pending.request());
    resumed.setIntent(Intent.RECOMMENDATION);
    resumed.setExplicitSearch(true);
    resumed.setRequestId(input.getRequestId());
    resumed.setRawText(input.getRawText());
    resumed.setCreatedAt(input.getCreatedAt());
    resumed.setSearchClarificationRequired(false);
    resumed.setDecision(null);
    resumed.setSimilarBookChoices(List.of());
    resumed
        .getSearchContext()
        .setSimilarBook(
            new SimilarBookReference(
                selected.bookId(), selected.title(), selected.authors(), null));
    return resumed;
  }

  private ConversationDraft anotherReference(ConversationTurn pending, SessionRequest input) {
    var another = restoreSearchCriteria(pending);
    another.setIntent(Intent.RECOMMENDATION);
    another.setExplicitSearch(true);
    another.setRequestId(input.getRequestId());
    another.setRawText(input.getRawText());
    another.setCreatedAt(input.getCreatedAt());
    another.setSimilarToTitle(null);
    another.setSimilarToAuthor(null);
    another.setSimilarToClarificationRequired(true);
    another.getSearchContext().setSimilarBook(null);
    another.getSearchContext().setSimilarityQueryVector(null);
    another.setSimilarBookChoices(List.of());
    another.setSearchClarificationRequired(false);
    another.setDecision(null);
    return another;
  }

  private static boolean isQuestion(ConversationDecision decision) {
    if (decision == null) {
      return false;
    }
    return switch (decision) {
      case COUNT_CLARIFICATION,
          EXPLANATION_TARGET_CLARIFICATION,
          PREFERENCE_CLARIFICATION,
          PREFERENCE_UPDATE_CLARIFICATION,
          SEARCH_CLARIFICATION,
          SHORTER_DURATION_CLARIFICATION,
          RECENCY_YEAR_CLARIFICATION,
          RECENCY_RANGE_CONFIRMATION,
          SIMILAR_BOOK_SELECTION,
          SIMILAR_BOOK_NOT_FOUND,
          SIMILAR_BOOK_REQUIRED,
          SIMILAR_BOOK_UNAVAILABLE ->
          true;
      default -> false;
    };
  }

  public void complete(Session session, ConversationDraft request) {
    Intent intent = request.getIntent();
    if (intent == Intent.REPEAT) {
      return;
    }
    if (intent == Intent.HELP) {
      request.setPendingQuestion(session.getPendingQuestion());
      if (session.getPendingQuestion() == ConversationDecision.EXPLANATION_TARGET_CLARIFICATION) {
        var search = ConversationHistory.latestSearch(session);
        if (search != null) {
          request.setRecommendations(search.getRecommendations());
        }
      } else if (session.getPendingSimilarBook() != null) {
        request.setSimilarBookChoices(
            session.getPendingSimilarBook().request().getSimilarBookChoices());
      }
      return;
    }
    if (intent == Intent.RESET_PREFERENCES) {
      return;
    }
    if (intent == Intent.UPDATE_PREFERENCES && session.getPendingQuestion() != null) {
      return;
    }
    if (intent == Intent.CHANGE_COUNT
        && request.getBookCount() != null
        && session.getPendingQuestion() != ConversationDecision.COUNT_CLARIFICATION) {
      return;
    }
    if (request.getDecision() == ConversationDecision.COUNT_CLARIFICATION
        && session.getPendingQuestion() != ConversationDecision.COUNT_CLARIFICATION) {
      session.setSuspendedQuestion(session.getPendingQuestion());
    } else if (intent == Intent.CHANGE_COUNT
        && request.getBookCount() != null
        && session.getPendingQuestion() == ConversationDecision.COUNT_CLARIFICATION) {
      session.setPendingQuestion(session.getSuspendedQuestion());
      session.setSuspendedQuestion(null);
      return;
    }
    if (intent == Intent.CANCEL) {
      session.setPendingRecencyRequest(null);
      session.setSuspendedQuestion(null);
    }
    if (isQuestion(request.getDecision())) {
      session.setPendingQuestion(request.getDecision());
    } else if (intent != Intent.UNKNOWN) {
      session.setPendingQuestion(null);
    }
    if (session.getPendingQuestion() != ConversationDecision.RECENCY_RANGE_CONFIRMATION
        && session.getSuspendedQuestion() != ConversationDecision.RECENCY_RANGE_CONFIRMATION) {
      session.setPendingRecencyRequest(null);
    }
  }
}
