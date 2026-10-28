package com.gen3.recommenderagent.application.conversation.workflow;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.inputparser.ConversationText;
import java.util.List;
import java.util.stream.Stream;

/**
 * Input normalization and fallback response decisions shared by parsing and conversation handlers.
 */
public final class ConversationRules {
  private ConversationRules() {}

  /** Shared eligibility rule for parsed inputs and restored conversation requests. */
  public static SessionRequest normalizeInput(SessionRequest request) {
    if (request.getIntent() == null) {
      request.setIntent(ConversationAction.UNKNOWN);
    }
    // Control intents may carry exact filters, but cannot introduce new semantic preferences.
    if (!acceptsSemanticInformation(request.getIntent())) {
      request.setPositiveSemanticQuery(null);
      request.setNegativeSemanticQuery(null);
      request.setPersonalised(false);
      request.setShorter(false);
      request.setLonger(false);
      request.setSimilarToTitle(null);
      request.setSimilarToAuthor(null);
      request.setSimilarToClarificationRequired(false);
    } else if (!request.getReplaceSearchFields().isEmpty()
        || request.isShorter()
        || request.isLonger()
        || (request.getMustInclude() != null
            && ConversationText.isYearRefinement(request.getMustInclude().getPublicationYear()))) {
      request.setIntent(ConversationAction.REFINE);
      request.setExplicitSearch(true);
    } else if (hasText(request.getSimilarToTitle()) || request.isSimilarToClarificationRequired()) {
      request.setExplicitSearch(true);
    }
    if (request.getIntent() != ConversationAction.EXPLAIN_RECOMMENDATION) {
      request.setExplanationSelection(null);
      request.setReferenceRank(null);
      request.setReferenceTitle(null);
    }
    return request;
  }

  static boolean acceptsSemanticInformation(ConversationAction intent) {
    return intent == ConversationAction.RECOMMENDATION || intent == ConversationAction.REFINE;
  }

  public static ConversationDecision decisionFor(
      List<Recommendation> recommendations, ConversationDraft request) {
    if (recommendations == null && request == null) {
      return ConversationDecision.UNAVAILABLE;
    }
    // Clarification outranks actions; actions outrank empty-result messages.
    if (request != null) {
      if (request.isSearchClarificationRequired()) {
        return ConversationDecision.SEARCH_CLARIFICATION;
      }
      if (request.isPreferenceClarificationRequired()) {
        return ConversationDecision.PREFERENCE_CLARIFICATION;
      }
      if (request.getIntent() != null) {
        ConversationDecision action =
            switch (request.getIntent()) {
              case EXPLAIN_RECOMMENDATION ->
                  recommendations == null || recommendations.isEmpty()
                      ? ConversationDecision.NOTHING_TO_EXPLAIN
                      : ConversationDecision.EXPLANATION;
              case CHANGE_COUNT ->
                  request.getBookCount() == null
                      ? ConversationDecision.COUNT_CLARIFICATION
                      : ConversationDecision.COUNT_CHANGED;
              case UPDATE_PREFERENCES -> ConversationDecision.PREFERENCES_UPDATED;
              case RESET_PREFERENCES -> ConversationDecision.PREFERENCES_RESET;
              case CLEAR_HISTORY -> ConversationDecision.HISTORY_CLEARED;
              case HELP -> ConversationDecision.HELP;
              case CANCEL -> ConversationDecision.CANCELLED;
              case UNKNOWN -> ConversationDecision.UNKNOWN;
              default -> null;
            };
        if (action != null) {
          return action;
        }
      }
    }
    if (recommendations == null || recommendations.isEmpty()) {
      if (request != null
          && (request.isNoMoreRecommendations()
              || request.getIntent() == ConversationAction.MORE_RESULTS)) {
        return ConversationDecision.NO_MORE_RESULTS;
      }
      if (request != null && request.getIntent() == ConversationAction.REPEAT_RECOMMENDATIONS) {
        return ConversationDecision.NO_RECOMMENDATIONS_TO_REPEAT;
      }
      if (request != null && request.getIntent() == ConversationAction.REPEAT) {
        return ConversationDecision.NOTHING_TO_REPEAT;
      }
      return ConversationDecision.NO_MATCHES;
    }
    return ConversationDecision.RECOMMENDATIONS;
  }

  static boolean isRecommendationIntent(ConversationAction intent) {
    return intent == ConversationAction.RECOMMENDATION
        || intent == ConversationAction.REFINE
        || intent == ConversationAction.MORE_RESULTS;
  }

  static boolean isSearchContext(ConversationDraft request) {
    return request != null && isRecommendationIntent(request.getIntent());
  }

  static boolean isSearchContext(ConversationTurn turn) {
    return turn != null && isRecommendationIntent(turn.getIntent());
  }

  static boolean hasSearchCriteria(ConversationDraft request) {
    if (request == null) {
      return false;
    }
    ExactCriteria include = request.getMustInclude();
    ExactCriteria exclude = request.getMustNotInclude();
    return request.isSimilarToClarificationRequired()
        || hasText(request.getSimilarToTitle())
        || request.getSearchContext().getSimilarBook() != null
        || request.isPersonalised()
        || hasQuery(request.getPositiveSemanticQuery())
        || hasQuery(request.getNegativeSemanticQuery())
        || (include != null
            && (hasTerms(include.getAuthors())
                || hasTerms(include.getNarrators())
                || hasText(
                    include.getLanguage(),
                    include.getDuration(),
                    include.getPublicationYear(),
                    include.getSource())))
        || (exclude != null
            && (hasTerms(exclude.getAuthors())
                || hasTerms(exclude.getNarrators())
                || hasText(
                    exclude.getLanguage(),
                    exclude.getDuration(),
                    exclude.getPublicationYear(),
                    exclude.getSource())));
  }

  static boolean hasQuery(SemanticQuery query) {
    return query != null && query.hasValues();
  }

  static boolean hasTerms(List<String> terms) {
    return terms != null && terms.stream().anyMatch(value -> value != null && !value.isBlank());
  }

  static boolean hasText(String... values) {
    return Stream.of(values).anyMatch(value -> value != null && !value.isBlank());
  }
}
