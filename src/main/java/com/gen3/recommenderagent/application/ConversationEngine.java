package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationState;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;

/** Coordinates conversation policy and retrieval within the caller-owned session transaction. */
@org.springframework.stereotype.Component
public class ConversationEngine {

  private final ConversationState conversation;

  private final RecommendationPager pager;
  private final ExactFilterPolicy planner;

  public ConversationEngine(
      ConversationState conversation, RecommendationPager pager, ExactFilterPolicy planner) {
    this.conversation = conversation;
    this.pager = pager;
    this.planner = planner;
  }

  public ConversationTurn process(Session session, SessionRequest request) {
    return ConversationTurn.fromDraft(apply(session, request));
  }

  private ConversationDraft apply(Session session, SessionRequest original) {
    ConversationDraft result =
        conversation.begin(
            session, original, resolved -> planner.validateTransition(resolved, original, session));
    if (conversation.needsRecommendations(result)) {
      result.setRecommendations(pager.page(result, session));
    }
    conversation.finish(session, result, original);
    String retrievalMode = conversation.retrievalMode(result);
    if (retrievalMode != null) {
      RequestTelemetry.retrieval(retrievalMode, result.getRecommendations().size());
    }
    return result;
  }
}
