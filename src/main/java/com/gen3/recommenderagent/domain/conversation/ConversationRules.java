package com.gen3.recommenderagent.domain.conversation;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import java.util.stream.Stream;

/**
 * Input normalization and fallback response decisions shared by parsing and conversation handlers.
 */
public final class ConversationRules {
  private ConversationRules() {}

  /** Shared eligibility rule for parsed inputs and restored conversation requests. */
  public static SessionRequest normalizeInput(SessionRequest request) {
    // Control intents may carry exact filters, but cannot introduce new semantic preferences.
    if (!acceptsSemanticInformation(request.getIntent())) {
      request.setPositiveSemanticQuery(null);
      request.setNegativeSemanticQuery(null);
      request.setPersonalised(false);
      request.setShorter(false);
      request.setSimilarToTitle(null);
      request.setSimilarToAuthor(null);
      request.setSimilarToClarificationRequired(false);
    } else if (request.isShorter()) {
      request.setIntent(Intent.REFINE);
      request.setExplicitSearch(true);
    } else if (hasText(request.getSimilarToTitle()) || request.isSimilarToClarificationRequired()) {
      request.setExplicitSearch(true);
    }
    if (request.getIntent() != Intent.EXPLAIN_RECOMMENDATION) {
      request.setReferenceRank(null);
      request.setReferenceTitle(null);
    }
    return request;
  }

  static boolean acceptsSemanticInformation(Intent intent) {
    return intent == Intent.RECOMMENDATION || intent == Intent.REFINE;
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
          && (request.isNoMoreRecommendations() || request.getIntent() == Intent.MORE_RESULTS)) {
        return ConversationDecision.NO_MORE_RESULTS;
      }
      if (request != null && request.getIntent() == Intent.REPEAT_RECOMMENDATIONS) {
        return ConversationDecision.NO_RECOMMENDATIONS_TO_REPEAT;
      }
      if (request != null && request.getIntent() == Intent.REPEAT) {
        return ConversationDecision.NOTHING_TO_REPEAT;
      }
      return ConversationDecision.NO_MATCHES;
    }
    return ConversationDecision.RECOMMENDATIONS;
  }

  static boolean isRecommendationIntent(Intent intent) {
    return intent == Intent.RECOMMENDATION
        || intent == Intent.REFINE
        || intent == Intent.MORE_RESULTS;
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
    return query != null
        && (hasTerms(query.getTopics())
            || hasTerms(query.getGenres())
            || hasTerms(query.getAuthors())
            || hasTerms(query.getNarrators())
            || hasTerms(query.getKeywords()));
  }

  static boolean hasTerms(List<String> terms) {
    return terms != null && terms.stream().anyMatch(value -> value != null && !value.isBlank());
  }

  static boolean hasText(String... values) {
    return Stream.of(values).anyMatch(value -> value != null && !value.isBlank());
  }
}
