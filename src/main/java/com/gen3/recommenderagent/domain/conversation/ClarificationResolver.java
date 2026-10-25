package com.gen3.recommenderagent.domain.conversation;

import static com.gen3.recommenderagent.domain.conversation.ConversationRules.normalizeInput;
import static com.gen3.recommenderagent.domain.conversation.SearchCriteriaMerger.copyOf;

import com.gen3.catalogue.model.AudiobookTitle;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
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
    if (effective.getIntent() == Intent.CANCEL) {
      normalizeInput(effective);
      return effective;
    }
    if (effective.getIntent() == Intent.UPDATE_PREFERENCES
        || (session.isAwaitingPreferenceUpdate()
            && session.getPendingQuestion() != ConversationDecision.EXPLANATION_TARGET_CLARIFICATION
            && session.getPendingQuestion() != ConversationDecision.COUNT_CLARIFICATION
            && session.getPendingQuestion() != ConversationDecision.SHORTER_DURATION_CLARIFICATION
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
    // The parser has no session context; resolve short answers against the outstanding question.
    Integer selection = ConversationText.selectionReply(reply);
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

  private ConversationDraft resolveSimilarBook(
      Session session, ConversationDraft input, String reply, Integer selection) {
    var pending = session.getPendingSimilarBook();
    // A bare choice belongs to the pending question, regardless of model-inferred search flags.
    if (selection == null
        && (input.isExplicitSearch()
            || input.getSimilarToTitle() != null
            || input.isSimilarToClarificationRequired())) {
      return input;
    }
    var choices = pending.request().getSimilarBookChoices();
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
                                      AudiobookTitle.normalize(author)
                                          .equals(AudiobookTitle.normalize(reply))))
              .toList();
      if (matches.size() == 1) {
        selected = matches.getFirst();
      }
    }
    if (selected == null) {
      if (!choices.isEmpty() && (selection != null || input.getIntent() == Intent.UNKNOWN)) {
        var again = pending.request().toDraft();
        again.setDecision(ConversationDecision.SIMILAR_BOOK_SELECTION);
        again.setSearchClarificationRequired(true);
        again.setSimilarBookChoices(choices);
        again.setRequestId(input.getRequestId());
        again.setRawText(input.getRawText());
        return again;
      }
      if (choices.isEmpty()
          && pending.decision() == ConversationDecision.SIMILAR_BOOK_REQUIRED
          && input.getIntent() == Intent.UNKNOWN
          && !reply.isBlank()) {
        var named = pending.request().toDraft();
        named.setRequestId(input.getRequestId());
        named.setRawText(input.getRawText());
        named.setCreatedAt(input.getCreatedAt());
        named.setSimilarToTitle(reply);
        named.setSearchClarificationRequired(false);
        named.setDecision(null);
        named.setExplicitSearch(true);
        return named;
      }
      if (choices.isEmpty()
          && pending.decision() == ConversationDecision.SIMILAR_BOOK_NOT_FOUND
          && (input.getIntent() == Intent.RECOMMENDATION || input.getIntent() == Intent.REFINE)) {
        var tastes = SearchCriteriaMerger.copyOf(input);
        SearchCriteriaMerger.merge(pending.request().toDraft(), tastes);
        tastes.setIntent(Intent.RECOMMENDATION);
        tastes.setExplicitSearch(true);
        session.setPendingSimilarBook(null);
        return tastes;
      }
      return input;
    }
    var resumed = pending.request().toDraft();
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
          SIMILAR_BOOK_SELECTION,
          SIMILAR_BOOK_NOT_FOUND,
          SIMILAR_BOOK_REQUIRED ->
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
      session.setSuspendedQuestion(null);
    }
    if (isQuestion(request.getDecision())) {
      session.setPendingQuestion(request.getDecision());
    } else if (intent != Intent.UNKNOWN) {
      session.setPendingQuestion(null);
    }
  }
}
