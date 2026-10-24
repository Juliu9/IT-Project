package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.domain.session.ConversationState;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Owns the single ordered load, transition and commit boundary for each session request. */
@Service
public class SessionTransitionService {
  private final SessionRepository sessions;
  private final ConversationEngine conversation;

  public SessionTransitionService(SessionRepository sessions, RecommendationWorkflow workflow) {
    this(sessions, new RecommendationPager(workflow), new ExactFilterPolicy());
  }

  public SessionTransitionService(
      SessionRepository sessions, RecommendationPager pager, ExactFilterPolicy filters) {
    this(sessions, new ConversationEngine(new ConversationState(), pager, filters));
  }

  @Autowired
  public SessionTransitionService(SessionRepository sessions, ConversationEngine conversation) {
    this.sessions = sessions;
    this.conversation = conversation;
  }

  public ConversationTurn process(String sessionId, String userId, SessionRequest request) {
    return sessions.update(sessionId, userId, session -> conversation.process(session, request));
  }
}
