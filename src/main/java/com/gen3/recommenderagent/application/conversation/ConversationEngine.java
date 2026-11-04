package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.application.conversation.workflow.ConversationDispatcher;
import com.gen3.recommenderagent.application.recommendation.RecommendationPager;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import java.util.ArrayList;

/** Coordinates conversation policy and retrieval within the caller-owned session transaction. */
@org.springframework.stereotype.Component
public class ConversationEngine {

  private final ConversationDispatcher conversation;

  private final com.gen3.recommenderagent.application.recommendation.SimilarBookResolver
      similarBooks;
  private final RecommendationPager pager;
  private final ExactFilterPolicy planner;

  /**
   * Connects conversation policy, paging, exact-filter validation, and reference-book resolution.
   */
  public ConversationEngine(
      ConversationDispatcher conversation,
      RecommendationPager pager,
      ExactFilterPolicy planner,
      com.gen3.recommenderagent.application.recommendation.SimilarBookResolver similarBooks) {
    this.similarBooks = similarBooks;
    this.conversation = conversation;
    this.pager = pager;
    this.planner = planner;
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
        conversation.begin(
            session,
            original,
            resolved -> {
              planner.validateTransition(resolved, original, session);
              similarBooks.resolve(session, resolved);
            });
    if (conversation.needsRecommendations(result)) {
      result.setRecommendations(pager.page(result, session));
    }
    // An unanswered taste question must not consume the previous completed search's batch.
    if (result.isPreferenceClarificationRequired()) {
      session.getActiveSearch().setPendingRecommendations(previousBatch);
    }
    conversation.finish(session, result, original);
    String retrievalMode = conversation.retrievalMode(result);
    if (retrievalMode != null) {
      RequestTelemetry.retrieval(retrievalMode, result.getRecommendations().size());
    }
    return result;
  }
}
