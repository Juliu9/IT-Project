package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.application.conversation.ConversationEngine;
import com.gen3.recommenderagent.application.conversation.IntentResolver;
import com.gen3.recommenderagent.application.conversation.SessionContextProjector;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.LastOutcome;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import com.gen3.recommenderagent.domain.session.PreferenceLimitException;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.SessionContext;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Owns the single ordered load, transition and commit boundary for each session request. */
@Service
public class SessionTransitionService {
  private final SessionRepository sessions;
  private final ConversationEngine conversation;
  private final IntentResolver intentResolver;
  private final SessionContextProjector contextProjector;

  /**
   * Connects session persistence to intent conversion, conversation execution, and parser context.
   */
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

  /** Loads a best-effort session snapshot and projects the bounded context used by the parser. */
  public SessionContext parserContext(String sessionId, String userId) {
    Session snapshot = sessions.snapshot(sessionId, userId);
    return contextProjector.project(snapshot);
  }

  /**
   * Applies one session transition under the repository update contract before returning its
   * result.
   */
  public ConversationTurn process(String sessionId, String userId, SessionRequest request) {
    return sessions.update(
        sessionId, userId, session -> transition(session, request.getIntent(), () -> request));
  }

  /**
   * Applies one session transition under the repository update contract before returning its
   * result.
   */
  public <T> T process(
      String sessionId,
      String userId,
      String rawText,
      UserIntent intent,
      Function<ConversationTurn, T> afterCommit) {
    return sessions.update(
        sessionId,
        userId,
        session ->
            transition(
                session,
                intent.getIntent(),
                () -> intentResolver.resolve(session, rawText, intent)),
        afterCommit);
  }

  /**
   * Executes conversation policy and turns preference category overflow into a repeatable response.
   */
  private ConversationTurn transition(
      Session session, ConversationAction action, Supplier<SessionRequest> input) {
    var pending = session.getPendingQuestion();
    try {
      var request = input.get();
      return RequestTelemetry.measure("recommendMs", () -> conversation.process(session, request));
    } catch (PreferenceLimitException exception) {
      if (action != ConversationAction.UPDATE_PREFERENCES
          && !(pending instanceof PendingQuestion.Preference)) {
        throw exception;
      }
      // Validation rejects the entire edit; keep its previous clarification as well.
      session.setPendingQuestion(pending);
      var rejected = new ConversationTurn();
      rejected.setIntent(ConversationAction.UPDATE_PREFERENCES);
      rejected.setOutcome(ConversationOutcome.PREFERENCE_LIMIT_EXCEEDED);
      rejected.setRecommendations(java.util.List.of());
      rejected.setRequestId(java.util.UUID.randomUUID().toString());
      rejected.setCreatedAt(java.time.Instant.now());
      session.setLastOutcome(new LastOutcome(rejected, rejected.getOutcome()));
      return rejected;
    }
  }
}
