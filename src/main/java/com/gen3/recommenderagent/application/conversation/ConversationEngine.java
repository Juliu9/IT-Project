package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.application.recommendation.RecommendationPager;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Coordinates conversation policy and retrieval within the caller-owned session transaction. */
@org.springframework.stereotype.Component
public class ConversationEngine {

  private final com.gen3.recommenderagent.application.conversation.HandlerRegistry handlers;
  private final com.gen3.recommenderagent.application.conversation.ClarificationResolver
      clarification;

  private final com.gen3.recommenderagent.application.recommendation.SimilarBookResolver
      similarBooks;
  private final RecommendationPager pager;
  private final ExactFilterPolicy planner;

  /**
   * Connects conversation policy, paging, exact-filter validation, and reference-book resolution.
   */
  public ConversationEngine(
      com.gen3.recommenderagent.application.conversation.HandlerRegistry handlers,
      RecommendationPager pager,
      ExactFilterPolicy planner,
      com.gen3.recommenderagent.application.recommendation.SimilarBookResolver similarBooks) {
    this.similarBooks = similarBooks;
    this.handlers = handlers;
    this.clarification =
        new com.gen3.recommenderagent.application.conversation.ClarificationResolver(handlers);
    this.pager = pager;
    this.planner = planner;
  }

  public ConversationTurn recoverFailure(
      Session session,
      ConversationAction action,
      com.gen3.recommenderagent.domain.session.PendingQuestion pending,
      RuntimeException exception) {
    return handlers.recoverFailure(session, action, pending, exception);
  }

  /** Runs a session conversation action and returns its completed turn snapshot. */
  public ConversationTurn process(Session session, SessionRequest request) {
    return ConversationDraft.toTurn(apply(session, request));
  }

  /**
   * Resolves and validates the action, obtains any required page, and completes session history
   * updates.
   */
  private ConversationDraft apply(Session session, SessionRequest original) {
    var previousBatch = new ArrayList<>(session.getActiveSearch().getPendingRecommendations());
    ConversationDraft result =
        begin(
            session,
            original,
            resolved -> {
              handler(resolved)
                  .validate(session, resolved, original, planner, similarBooks, handlers);
            });
    if (needsRecommendations(result)) {
      result.setRecommendations(pager.page(result, session));
    }
    // An unanswered taste question must not consume the previous completed search's batch.
    if (result.isPreferenceClarificationRequired()) {
      session.getActiveSearch().setPendingRecommendations(previousBatch);
    }
    finish(session, result, original);
    String retrievalMode = handler(result).retrievalMode(result);
    if (retrievalMode != null) {
      RequestTelemetry.retrieval(retrievalMode, result.getRecommendations().size());
    }
    return result;
  }

  /** Resolves and validates an action before invalidating any cached recommendation batch. */
  public ConversationDraft begin(Session session, SessionRequest original) {
    return begin(session, original, request -> {});
  }

  /** Resolves and validates an action before invalidating any cached recommendation batch. */
  public ConversationDraft begin(
      Session session, SessionRequest original, Consumer<ConversationDraft> validate) {
    var request = resolve(session, original);
    validate.accept(request);
    // A selection can become a new search; unchanged MORE still checks only its incoming filters.
    SessionRequest batchChange =
        original == null || request.getIntent() != original.getIntent() ? request : original;
    if (!request.isSearchClarificationRequired()
        && handler(request).invalidatesBatch(batchChange)) {
      session.getActiveSearch().setPendingRecommendations(List.of());
    }
    return request;
  }

  /** Applies pending-question policy and delegates the effective action to its handler. */
  public ConversationDraft resolve(Session session, SessionRequest original) {
    var input = clarification.resolve(session, original);
    return handler(input).resolve(session, input);
  }

  /** Looks up the registered handler for the draft's effective intent. */
  private ConversationHandler handler(ConversationDraft request) {
    return handlers.handler(request.getIntent());
  }

  /** Reports whether the resolved action requires a recommendation page. */
  public boolean needsRecommendations(ConversationDraft request) {
    return handler(request).needsRecommendations(request);
  }

  /**
   * Completes handler state, pending questions, retained search context, and the repeatable
   * outcome.
   */
  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    var handler = handler(request);
    handler.finish(session, request, original);
    ConversationHistory.complete(request);
    clarification.complete(session, request);
    if (handler.retainsHistory()
        && !request.isSearchClarificationRequired()
        && !request.isPreferenceClarificationRequired()) {
      ConversationHistory.retainRequest(
          session, ConversationHistory.historyEntry(request), handler.isSearchAction());
    }
    if (handler.remembersTurn(request)) {
      ConversationHistory.remember(session, request);
    }
  }
}
