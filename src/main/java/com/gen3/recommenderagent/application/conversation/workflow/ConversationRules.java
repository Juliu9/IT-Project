package com.gen3.recommenderagent.application.conversation.workflow;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
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
      request.setStartsNewSearch(true);
    } else if (hasText(request.getSimilarToTitle()) || request.isSimilarToClarificationRequired()) {
      request.setStartsNewSearch(true);
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
