package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.application.conversation.intent.UnknownHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import java.util.List;
import java.util.stream.Stream;

/**
 * Input normalization and fallback response decisions shared by parsing and conversation handlers.
 */
public final class ConversationRules {
  /** Prevents instantiation of this utility class. */
  private ConversationRules() {}

  /** Applies field eligibility using the registry actually executing this request. */
  public static SessionRequest normalizeInput(SessionRequest request, HandlerRegistry handlers) {
    var action = request.getIntent() == null ? UnknownHandler.ACTION : request.getIntent();
    return normalizeInput(request, handlers.handler(action));
  }

  /** Applies the supplied intent's policy without a global registry or standalone discovery. */
  public static SessionRequest normalizeInput(SessionRequest request, ConversationHandler handler) {
    if (request.getIntent() == null) {
      request.setIntent(UnknownHandler.ACTION);
    }
    if (!handler.acceptsSearchSemantics()) {
      request.setPositiveSemanticQuery(null);
      request.setNegativeSemanticQuery(null);
      request.setPersonalised(false);
      request.setShorter(false);
      request.setLonger(false);
      request.setSimilarToTitle(null);
      request.setSimilarToAuthor(null);
      request.setSimilarToClarificationRequired(false);
    }
    handler.normalizeFields(request);
    if (!handler.retainsExplanationSelection()) {
      request.setExplanationSelection(null);
      request.setReferenceRank(null);
      request.setReferenceTitle(null);
    }
    return request;
  }

  /** Lets a pending selection handle a numeric reply that parsing treated as an invalid count. */
  public static void reinterpretSelectionCount(ConversationDraft request, String reply) {
    if (request.isInvalidBookCount() && reply.matches("[-−]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")) {
      request.setInvalidBookCount(false);
      request.setIntent(UnknownHandler.ACTION);
      request.setBookCount(null);
    }
  }

  /** Reports whether any usable semantic, exact, personalised, or reference-book criteria exist. */
  public static boolean hasSearchCriteria(ConversationDraft request) {
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

  /** Reports whether the semantic query contains any usable preference values. */
  public static boolean hasQuery(SemanticQuery query) {
    return query != null && query.hasValues();
  }

  /** Reports whether the supplied list contains at least one nonblank term. */
  public static boolean hasTerms(List<String> terms) {
    return terms != null && terms.stream().anyMatch(value -> value != null && !value.isBlank());
  }

  /** Reports whether the supplied text contains a non-whitespace character. */
  public static boolean hasText(String... values) {
    return Stream.of(values).anyMatch(value -> value != null && !value.isBlank());
  }
}
