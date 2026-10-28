package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.application.conversation.ConversationEngine;
import com.gen3.recommenderagent.application.conversation.IntentResolver;
import com.gen3.recommenderagent.application.conversation.SessionContextProjector;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.SessionContext;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Owns the single ordered load, transition and commit boundary for each session request. */
@Service
public class SessionTransitionService {
  private final SessionRepository sessions;
  private final ConversationEngine conversation;
  private final IntentResolver intentResolver;
  private final SessionContextProjector contextProjector;

  @org.springframework.beans.factory.annotation.Autowired
  public SessionTransitionService(
      SessionRepository sessions,
      ConversationEngine conversation,
      IntentResolver intentResolver,
      SessionContextProjector contextProjector) {
    this.sessions = sessions;
    this.conversation = conversation;
    this.intentResolver = intentResolver;
    this.contextProjector = contextProjector;
  }

  /** Compatibility constructor used by existing focused tests. */
  public SessionTransitionService(SessionRepository sessions, ConversationEngine conversation) {
    this(sessions, conversation, null, null);
  }

  public SessionContext parserContext(String sessionId, String userId) {
    Session snapshot = sessions.snapshot(sessionId, userId);
    return contextProjector == null ? SessionContext.EMPTY : contextProjector.project(snapshot);
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

  public <T> T process(
      String sessionId,
      String userId,
      String rawText,
      UserIntent intent,
      Function<ConversationTurn, T> afterCommit) {
    return sessions.update(
        sessionId,
        userId,
        session -> {
          if (intentResolver == null) {
            throw new IllegalStateException("User intents require an IntentResolver");
          }
          SessionRequest request = intentResolver.resolve(session, rawText, intent);
          return RequestTelemetry.measure(
              "recommendMs", () -> conversation.process(session, request));
        },
        afterCommit);
  }
}
