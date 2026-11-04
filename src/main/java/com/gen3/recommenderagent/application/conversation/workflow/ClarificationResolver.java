package com.gen3.recommenderagent.application.conversation.workflow;

import static com.gen3.recommenderagent.application.conversation.workflow.ConversationRules.normalizeInput;
import static com.gen3.recommenderagent.application.conversation.workflow.SearchCriteriaMerger.copyOf;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.PreferenceRequestMapper;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.ConversationText;
import java.util.List;

/**
 * Resolves short replies and cancellation against the outstanding question before intent dispatch.
 */
public final class ClarificationResolver {
  private final List<PendingQuestionHandler<?>> pendingQuestionHandlers =
      List.of(new SimilarBookPendingQuestionHandler());

  /** Interprets pending-question replies and cancellation before ordinary intent dispatch. */
  public ConversationDraft resolve(Session session, SessionRequest input) {
    ConversationDraft effective = copyOf(input);
    if (session == null) {
      return effective;
    }
    String reply = effective.getRawText() == null ? "" : effective.getRawText().strip();
    resolveInvalidSelection(session, effective, reply);
    boolean contextual = isContextualReply(session, effective, reply);
    if (effective.getIntent() == ConversationAction.EXPLAIN_PREFERENCES
        || effective.getIntent() == ConversationAction.HELP
        || (effective.getIntent() == ConversationAction.REPEAT && !contextual)) {
      return effective;
    }
    ConversationDraft resolved = resolveCancellation(session, effective, reply);
    if (resolved != null) {
      return resolved;
    }
    if (isNewAction(effective) && !contextual) {
      session.setPendingQuestion(null);
      return effective;
    }
    resolved = resolveDurationReply(session, effective, reply);
    if (resolved != null) {
      return resolved;
    }
    resolved = resolveSearchChangeReply(session, effective, reply);
    if (resolved != null) {
      return resolved;
    }
    resolved = resolvePreferenceReply(session, effective);
    if (resolved != null) {
      return resolved;
    }
    resolved = resolveRangeConfirmation(session, effective, reply);
    if (resolved != null) {
      return resolved;
    }
    resolved = resolveExplanationSelection(session, effective, reply);
    if (resolved != null) {
      return resolved;
    }
    Integer selection = resolveSelection(session, reply);
    resolved = resolveYearReply(session, effective, reply);
    if (resolved != null) {
      return resolved;
    }
    resolved = resolveExplanationTarget(session, effective, reply, selection);
    if (resolved != null) {
      return resolved;
    }
    var handler = handlerFor(session.getPendingQuestion());
    if (handler != null) {
      resolved = handler.resolve(session, effective, reply);
      if (resolved != effective) {
        return resolved;
      }
    }
    if (effective.getIntent() == ConversationAction.UNKNOWN
        && session.getPendingQuestion() != null) {
      attachQuestion(session, effective);
    } else {
      clearAbandonedQuestions(session, effective);
    }
    return effective;
  }

  /** Recognizes cancellation and preference declines against the current unfinished action. */
  private ConversationDraft resolveCancellation(
      Session session, ConversationDraft effective, String reply) {
    if ((session.getPendingQuestionKind() == ConversationQuestion.SEARCH_CHANGE_OFFER)
        && ConversationText.isPreferenceDecline(reply)) {
      effective.setIntent(ConversationAction.CANCEL);
      normalizeInput(effective);
      effective.setOutcome(ConversationOutcome.SEARCH_DECLINED);
      return effective;
    }
    if (ConversationText.isCancellationReply(reply)
        || (session.getPendingQuestionKind() != null
            && ConversationText.normalizeCommand(reply).equals("no"))) {
      effective.setIntent(ConversationAction.CANCEL);
    }
    if ((session.getPendingQuestionKind() == ConversationQuestion.PREFERENCE_CLARIFICATION
            || session.getPendingQuestionKind()
                == ConversationQuestion.PREFERENCE_UPDATE_CLARIFICATION)
        && ConversationText.isPreferenceDecline(reply)) {
      effective.setIntent(ConversationAction.CANCEL);
    }
    if (effective.getIntent() == ConversationAction.CANCEL) {
      normalizeInput(effective);
      return effective;
    }
    return null;
  }

  /** Applies a duration answer to the retained shorter-or-longer refinement request. */
  private ConversationDraft resolveDurationReply(
      Session session, ConversationDraft effective, String reply) {
    if (session.getPendingQuestionKind() == ConversationQuestion.SHORTER_DURATION_CLARIFICATION
        || session.getPendingQuestionKind() == ConversationQuestion.LONGER_DURATION_CLARIFICATION) {
      String duration =
          ConversationText.durationReply(
              reply,
              session.getPendingQuestionKind()
                  == ConversationQuestion.LONGER_DURATION_CLARIFICATION);
      if (duration == null && ConversationText.isDurationOnlyReply(reply)) {
        var clarification =
            session.getPendingRefinementRequest() == null
                ? effective
                : restoreSearchCriteria(session.getPendingRefinementRequest(), effective);
        clarification.setIntent(ConversationAction.REFINE);
        clarification.setPendingQuestion(session.getPendingQuestionKind());
        clarification.setSearchClarificationRequired(true);
        return clarification;
      }
      boolean parsedReply =
          !effective.startsNewSearch()
              && effective.getMustInclude() != null
              && effective.getMustInclude().getDuration() != null
              && (effective.getIntent() == ConversationAction.RECOMMENDATION
                  || effective.getIntent() == ConversationAction.REFINE
                  || effective.getIntent() == ConversationAction.UNKNOWN);
      if (duration != null || parsedReply) {
        effective.setIntent(ConversationAction.REFINE);
        effective.setShorter(false);
        effective.setLonger(false);
        if (duration != null) {
          var include = new com.gen3.recommenderagent.domain.search.ExactCriteria();
          include.setDuration(duration);
          effective.setMustInclude(include);
          effective.setMustNotInclude(null);
          effective.setPositiveSemanticQuery(null);
          effective.setNegativeSemanticQuery(null);
          effective.setPersonalised(false);
          effective.setBookCount(null);
          effective.setInvalidBookCount(false);
        }
        normalizeInput(effective);
        if (session.getPendingRefinementRequest() != null) {
          SearchCriteriaMerger.merge(
              restoreSearchCriteria(session.getPendingRefinementRequest()), effective);
        }
        return effective;
      }
    }
    return null;
  }

  /** Restores an unfinished search when the user accepts or answers its clarification. */
  private ConversationDraft resolveSearchChangeReply(
      Session session, ConversationDraft effective, String reply) {
    if (ConversationText.isAffirmativeReply(reply)
        && (session.getPendingQuestionKind() == ConversationQuestion.SEARCH_CHANGE_OFFER)) {
      var previous = ConversationHistory.latestDisplayedSearch(session);
      if (previous != null) {
        session.setPendingRefinementRequest(ConversationDraft.toStoredTurn(previous));
      }
      effective.setIntent(ConversationAction.RECOMMENDATION);
      normalizeInput(effective);
      effective.setPendingQuestion(ConversationQuestion.SEARCH_CHANGE_CLARIFICATION);
      effective.setSearchClarificationRequired(true);
      return effective;
    }
    if ((session.getPendingQuestionKind() == ConversationQuestion.SEARCH_CLARIFICATION
            || session.getPendingQuestionKind() == ConversationQuestion.SEARCH_CHANGE_CLARIFICATION)
        && session.getPendingRefinementRequest() != null
        && !effective.startsNewSearch()
        && (effective.getIntent() == ConversationAction.RECOMMENDATION
            || effective.getIntent() == ConversationAction.REFINE)) {
      var pending = session.getPendingRefinementRequest();
      effective.setIntent(
          session.getPendingQuestionKind() == ConversationQuestion.SEARCH_CLARIFICATION
              ? ConversationAction.RECOMMENDATION
              : ConversationAction.REFINE);
      if (effective.getBookCount() == null) {
        effective.setBookCount(pending.getBookCount());
      }
      SearchCriteriaMerger.merge(restoreSearchCriteria(pending), effective);
      session.setPendingRefinementRequest(null);
      session.setPendingQuestionKind(null);
    }
    return null;
  }

  /** Treats invalid numeric selection replies as question answers rather than count changes. */
  private void resolveInvalidSelection(Session session, ConversationDraft effective, String reply) {
    if (effective.isInvalidBookCount()
        && (session.getPendingQuestionKind()
                == ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION
            || session.getPendingQuestion() instanceof PendingQuestion.SimilarBook)
        && reply.matches("[-−]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")) {
      effective.setInvalidBookCount(false);
      effective.setIntent(ConversationAction.UNKNOWN);
      effective.setBookCount(null);
    }
  }

  /** Interprets supplied taste values as an explicit or pending saved-preference update. */
  private ConversationDraft resolvePreferenceReply(Session session, ConversationDraft effective) {
    if (effective.getIntent() == ConversationAction.UPDATE_PREFERENCES
        || (session.isAwaitingPreferenceUpdate()
            && !session.isAwaitingPersonalisedPreferences()
            && session.getPendingQuestionKind()
                != ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION
            && !isRefinementQuestion(session.getPendingQuestionKind())
            && !effective.startsNewSearch()
            && (effective.getIntent() == ConversationAction.RECOMMENDATION
                || effective.getIntent() == ConversationAction.REFINE
                || effective.getIntent() == ConversationAction.UNKNOWN)
            && PreferenceRequestMapper.fromRequest(effective).hasValues())) {
      effective.setIntent(ConversationAction.UPDATE_PREFERENCES);
      if (effective.getPreferenceUpdates() == null) {
        effective.setPreferenceUpdates(PreferenceRequestMapper.fromRequest(effective));
      }
    }
    return null;
  }

  /** Resumes or cancels a proposed range change after the user's confirmation. */
  private ConversationDraft resolveRangeConfirmation(
      Session session, ConversationDraft effective, String reply) {
    if ((session.getPendingQuestionKind() == ConversationQuestion.RECENCY_RANGE_CONFIRMATION
            || session.getPendingQuestionKind() == ConversationQuestion.DURATION_RANGE_CONFIRMATION)
        && session.getPendingRefinementRequest() != null) {
      String answer = ConversationText.normalizeCommand(reply);
      if (java.util.Set.of("no", "no thanks", "not now", "keep the previous search")
          .contains(answer)) {
        effective.setIntent(ConversationAction.CANCEL);
        normalizeInput(effective);
        return effective;
      }
      if (ConversationText.isAffirmativeReply(reply)) {
        var resumed = restoreSearchCriteria(session.getPendingRefinementRequest(), effective);
        resumed.setSearchClarificationRequired(false);
        resumed.setOutcome(null);
        session.setPendingQuestionKind(null);
        session.setPendingRefinementRequest(null);
        return resumed;
      }
    }
    return null;
  }

  /** Resolves whole-page or replacement selections for a pending explanation. */
  private ConversationDraft resolveExplanationSelection(
      Session session, ConversationDraft effective, String reply) {
    // The parser has no session context; resolve short answers against the outstanding question.
    if (session.getPendingQuestionKind() == ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION
        && ConversationText.normalizeCommand(reply).matches("[-−]?(?:[0-9]+\\.[0-9]+|\\.[0-9]+)")) {
      effective.setIntent(ConversationAction.EXPLAIN_RECOMMENDATION);
      normalizeInput(effective);
      effective.setInvalidBookCount(false);
      effective.setReferenceRank(0);
      effective.setReferenceTitle(null);
      effective.setExplanationSelection(null);
      return effective;
    }
    if (session.getPendingQuestionKind() == ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION
        && ConversationText.isWholePageReply(reply)) {
      effective.setIntent(ConversationAction.EXPLAIN_RECOMMENDATION);
      normalizeInput(effective);
      effective.setReferenceRank(null);
      effective.setReferenceTitle(null);
      effective.setExplanationSelection(null);
      session.setPendingExplanation(null);
      return effective;
    }
    if (session.getPendingQuestionKind() == ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION
        && session.getPendingExplanation() != null
        && !effective.startsNewSearch()
        && (effective.getIntent() == ConversationAction.UNKNOWN
            || effective.getIntent() == ConversationAction.RECOMMENDATION
            || effective.getIntent() == ConversationAction.REFINE)) {
      var search = ConversationHistory.latestDisplayedSearch(session);
      var page = search == null ? List.<Recommendation>of() : search.getRecommendations();
      String unresolved =
          ExplanationConversationHandler.firstUnresolved(session.getPendingExplanation(), page);
      if (unresolved != null
          && (ConversationText.selectionReply(reply) != null
              || ExplanationConversationHandler.matchesDisplayedTitle(session, reply))) {
        effective.setIntent(ConversationAction.EXPLAIN_RECOMMENDATION);
        normalizeInput(effective);
        effective.setReferenceRank(null);
        effective.setReferenceTitle(null);
        effective.setExplanationSelection(
            session.getPendingExplanation().replace(unresolved, reply));
        return effective;
      }
    }
    return null;
  }

  /** Maps numeric or relative selection replies to ranks on the displayed page. */
  private Integer resolveSelection(Session session, String reply) {
    Integer selection = ConversationText.selectionReply(reply);
    if (session.getPendingQuestionKind() == ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION) {
      var search = ConversationHistory.latestDisplayedSearch(session);
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
    return selection;
  }

  /** Applies a year answer to an unfinished recency refinement. */
  private ConversationDraft resolveYearReply(
      Session session, ConversationDraft effective, String reply) {
    String yearReply = ConversationText.normalizeCommand(reply);
    if ((session.getPendingQuestionKind() == ConversationQuestion.RECENCY_YEAR_CLARIFICATION
            || session.getPendingQuestionKind() == ConversationQuestion.RECENCY_RANGE_CONFIRMATION)
        && !effective.startsNewSearch()
        && (effective.getIntent() == ConversationAction.UNKNOWN
            || effective.getIntent() == ConversationAction.RECOMMENDATION
            || effective.getIntent() == ConversationAction.REFINE)
        && (yearReply.matches("[0-9]{4}")
            || (effective.getMustInclude() != null
                && effective.getMustInclude().getPublicationYear() != null))) {
      effective.setIntent(ConversationAction.REFINE);
      normalizeInput(effective);
      var include =
          effective.getMustInclude() == null
              ? new com.gen3.recommenderagent.domain.search.ExactCriteria()
              : effective.getMustInclude();
      if (yearReply.matches("[0-9]{4}")) {
        include.setPublicationYear("from " + yearReply);
      } else if (include.getPublicationYear().matches("[0-9]{4}")) {
        include.setPublicationYear("from " + include.getPublicationYear());
      }
      effective.setMustInclude(include);
      boolean older =
          session.getPendingRefinementRequest() != null
              && session.getPendingRefinementRequest().getMustInclude() != null
              && ConversationText.isOlderRefinement(
                  session.getPendingRefinementRequest().getMustInclude().getPublicationYear());
      if (older && yearReply.matches("[0-9]{4}")) {
        include.setPublicationYear("at most " + yearReply);
      }
      if (session.getPendingRefinementRequest() != null) {
        SearchCriteriaMerger.merge(
            restoreSearchCriteria(session.getPendingRefinementRequest()), effective);
      }
      if (older
          || session.getPendingQuestionKind() == ConversationQuestion.RECENCY_RANGE_CONFIRMATION) {
        session.setPendingQuestionKind(null);
        session.setPendingRefinementRequest(null);
      }
      return effective;
    }
    return null;
  }

  /** Converts a pending rank or displayed-title answer into an explanation request. */
  private ConversationDraft resolveExplanationTarget(
      Session session, ConversationDraft effective, String reply, Integer selection) {
    if (session.getPendingQuestionKind() == ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION
        && (selection != null
            || (!effective.startsNewSearch()
                && !reply.isBlank()
                && ((effective.getIntent() == ConversationAction.UNKNOWN
                        || effective.getIntent() == ConversationAction.RECOMMENDATION
                        || effective.getIntent() == ConversationAction.REFINE)
                    && ExplanationConversationHandler.matchesDisplayedTitle(session, reply))))) {
      effective.setIntent(ConversationAction.EXPLAIN_RECOMMENDATION);
      normalizeInput(effective);
      effective.setReferenceRank(selection);
      effective.setReferenceTitle(selection == null ? reply : null);
      return effective;
    }
    return null;
  }

  /** Checks whether the reply answers the current question rather than starting another action. */
  private boolean isContextualReply(Session session, ConversationDraft request, String reply) {
    if (session.getPendingQuestion() == null) {
      return false;
    }
    ConversationQuestion decision = session.getPendingQuestionKind();
    if ((decision == ConversationQuestion.SHORTER_DURATION_CLARIFICATION
            || decision == ConversationQuestion.LONGER_DURATION_CLARIFICATION)
        && ConversationText.isDurationOnlyReply(reply)) {
      return true;
    }
    if (decision == ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION) {
      if (ConversationText.selectionReply(reply) != null
          || ConversationText.relativeSelectionReply(reply, 5) != null) {
        return true;
      }
    }
    var handler = handlerFor(session.getPendingQuestion());
    return handler != null && handler.isContextualReply(session, request, reply);
  }

  /** Reports whether the incoming action abandons the previous pending question. */
  private boolean isNewAction(ConversationDraft request) {
    return switch (request.getIntent()) {
      case UPDATE_PREFERENCES,
          RESET_PREFERENCES,
          CHANGE_COUNT,
          CLEAR_HISTORY,
          MORE_RESULTS,
          REPEAT_RECOMMENDATIONS,
          EXPLAIN_RECOMMENDATION,
          CANCEL ->
          true;
      case RECOMMENDATION, REFINE -> request.startsNewSearch();
      default -> false;
    };
  }

  /** Clears stale clarification state when the action no longer needs it. */
  private void clearAbandonedQuestions(Session session, ConversationDraft request) {
    if (request.getIntent() != ConversationAction.UNKNOWN
        && !session.isAwaitingPersonalisedPreferences()
        && !session.isAwaitingPreferenceUpdate()) {
      session.setPendingQuestion(null);
    }
  }

  /** Restores explicit search criteria so current saved preferences can be applied again. */
  static ConversationDraft restoreSearchCriteria(ConversationTurn pending, SessionRequest reply) {
    var resumed = restoreSearchCriteria(pending);
    resumed.setRequestId(reply.getRequestId());
    resumed.setRawText(reply.getRawText());
    resumed.setCreatedAt(reply.getCreatedAt());
    return resumed;
  }

  /** Restores explicit search criteria so current saved preferences can be applied again. */
  static ConversationDraft restoreSearchCriteria(ConversationTurn pending) {
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

  /** Finds the reply handler registered for the pending question's persisted type. */
  private PendingQuestionHandler<?> handlerFor(PendingQuestion question) {
    if (question == null) {
      return null;
    }
    return pendingQuestionHandlers.stream()
        .filter(handler -> handler.questionType().isInstance(question))
        .findFirst()
        .orElse(null);
  }

  /** Reports whether an empty result should offer a change to the current search. */
  private static boolean offersSearchChange(ConversationDraft request) {
    return request.getOutcome() == ConversationOutcome.EMPTY_EXPLANATION_PAGE
        || (ConversationRules.isSearchContext(request)
            && request.getRecommendations() != null
            && request.getRecommendations().isEmpty());
  }

  /** Identifies pending questions that retain an unfinished search refinement. */
  private boolean isRefinementQuestion(ConversationQuestion decision) {
    return decision == ConversationQuestion.SEARCH_CLARIFICATION
        || decision == ConversationQuestion.SEARCH_CHANGE_CLARIFICATION
        || decision == ConversationQuestion.RECENCY_RANGE_CONFIRMATION
        || decision == ConversationQuestion.RECENCY_YEAR_CLARIFICATION
        || decision == ConversationQuestion.SHORTER_DURATION_CLARIFICATION
        || decision == ConversationQuestion.LONGER_DURATION_CLARIFICATION
        || decision == ConversationQuestion.DURATION_RANGE_CONFIRMATION;
  }

  /** Copies retained question context into the outgoing conversation draft. */
  private void attachQuestion(Session session, ConversationDraft request) {
    request.setPendingQuestion(session.getPendingQuestionKind());
    if (session.getPendingQuestionKind() == ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION) {
      request.setExplanationSelection(session.getPendingExplanation());
      var search = ConversationHistory.latestDisplayedSearch(session);
      if (search != null) {
        request.setRecommendations(search.getRecommendations());
      }
    } else if (isRefinementQuestion(session.getPendingQuestionKind())
        && session.getPendingRefinementRequest() != null) {
      request.setMustInclude(
          restoreSearchCriteria(session.getPendingRefinementRequest()).getMustInclude());
    } else if (session.getPendingQuestion() instanceof PendingQuestion.SimilarBook pending) {
      request.setSimilarBookChoices(pending.getRequest().getSimilarBookChoices());
    }
  }

  /** Persists the next pending question while preserving it for read-only and replay actions. */
  public void complete(Session session, ConversationDraft request) {
    ConversationAction intent = request.getIntent();
    if (intent == ConversationAction.REPEAT || intent == ConversationAction.EXPLAIN_PREFERENCES) {
      return;
    }
    if (intent == ConversationAction.HELP || intent == ConversationAction.UNKNOWN) {
      attachQuestion(session, request);
      return;
    }
    ConversationOutcome decision = request.getOutcome();
    ConversationQuestion question = request.getPendingQuestion();
    if (decision != null
        && decision != ConversationOutcome.EMPTY_EXPLANATION_PAGE
        && decision != ConversationOutcome.EMPTY_EXPLANATION_SELECTION) {
      question = null;
    }
    boolean questionSetByHandler = question != null;
    if (decision == null && question == null && request.isSearchClarificationRequired()) {
      question = ConversationQuestion.SEARCH_CLARIFICATION;
      questionSetByHandler = true;
    } else if (decision == null
        && question == null
        && request.isPreferenceClarificationRequired()) {
      question = ConversationQuestion.PREFERENCE_CLARIFICATION;
      questionSetByHandler = true;
    } else if (decision == ConversationOutcome.EMPTY_EXPLANATION_SELECTION) {
      question = ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION;
    } else if (question == null && offersSearchChange(request)) {
      question = ConversationQuestion.SEARCH_CHANGE_OFFER;
    }
    request.setPendingQuestion(question);
    if (question != null) {
      if (questionSetByHandler) {
        request.setOutcome(null);
      }
      session.setPendingQuestionKind(question);
      if (session.getPendingQuestion()
          instanceof com.gen3.recommenderagent.domain.session.PendingQuestion.Preference pending) {
        pending.setRequest(ConversationDraft.toStoredTurn(request));
      }
    } else {
      session.setPendingQuestion(null);
    }
  }
}
