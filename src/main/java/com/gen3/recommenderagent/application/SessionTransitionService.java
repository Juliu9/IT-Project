package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.application.conversation.ConversationEngine;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Owns the single ordered load, transition and commit boundary for each session request. */
@Service
public class SessionTransitionService {
  private final SessionRepository sessions;
  private final ConversationEngine conversation;

  public SessionTransitionService(SessionRepository sessions, ConversationEngine conversation) {
    this.sessions = sessions;
    this.conversation = conversation;
  }

  public ConversationTurn process(String sessionId, String userId, SessionRequest request) {
    return sessions.update(sessionId, userId, session -> conversation.process(session, request));
  }

  public <T> T process(
      String sessionId,
      String userId,
      SessionRequest request,
      Function<ConversationTurn, T> afterCommit) {
    return sessions.update(
        sessionId,
        userId,
        session ->
            RequestTelemetry.measure("recommendMs", () -> conversation.process(session, request)),
        afterCommit);
  }
}
