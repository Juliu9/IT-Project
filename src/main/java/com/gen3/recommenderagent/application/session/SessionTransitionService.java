package com.gen3.recommenderagent.application.session;

import com.gen3.recommenderagent.application.conversation.ConversationEngine;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.SessionContext;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Owns the single ordered load, transition and commit boundary for each session request. */
@Service
public class SessionTransitionService {
  private final SessionRepository sessions;
  private final ConversationEngine conversation;
  private final com.gen3.recommenderagent.inputparser.InputParser inputParser;
  private static final int MAX_DISPLAYED_BOOKS = 5;

  /**
   * Connects session persistence to intent conversion, conversation execution, and parser context.
   */
  public SessionTransitionService(
      SessionRepository sessions,
      ConversationEngine conversation,
      com.gen3.recommenderagent.inputparser.InputParser inputParser) {
    this.sessions = sessions;
    this.conversation = conversation;
    this.inputParser = inputParser;
  }

  /** Loads a best-effort session snapshot and projects the bounded context used by the parser. */
  public SessionContext parserContext(String sessionId, String userId) {
    Session snapshot = sessions.snapshot(sessionId, userId);
    return projectParserContext(snapshot);
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
                session, intent.getIntent(), () -> inputParser.toSessionRequest(rawText, intent)),
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
      RequestTelemetry.captureRequest(request);
      return RequestTelemetry.measure("recommendMs", () -> conversation.process(session, request));
    } catch (com.gen3.recommenderagent.inputparser.InvalidRequestException exception) {
      var recovered = conversation.recoverFailure(session, action, pending, exception);
      if (recovered == null) {
        throw exception;
      }
      return recovered;
    }
  }

  /** Extracts bounded parser context from the pending question, last action, and displayed page. */
  private SessionContext projectParserContext(Session session) {
    if (session == null) {
      return SessionContext.EMPTY;
    }
    String pending =
        session.getPendingQuestion() == null ? null : session.getPendingQuestion().getKind().name();
    String action =
        session.getLastOutcome() == null
                || session.getLastOutcome().turn() == null
                || session.getLastOutcome().turn().getIntent() == null
            ? null
            : session.getLastOutcome().turn().getIntent().name();
    List<SessionContext.DisplayedBook> displayed =
        session.getActiveSearch() == null
                || session.getActiveSearch().getSearch() == null
                || session.getActiveSearch().getSearch().getRecommendations() == null
            ? List.of()
            : session.getActiveSearch().getSearch().getRecommendations().stream()
                .filter(book -> book.getTitle() != null)
                .limit(MAX_DISPLAYED_BOOKS)
                .map(book -> new SessionContext.DisplayedBook(book.getRank(), book.getTitle()))
                .toList();
    return new SessionContext(pending, action, displayed);
  }
}
