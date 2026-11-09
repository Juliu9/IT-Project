package com.gen3.recommenderagent.application.conversation;

import static com.gen3.recommenderagent.application.conversation.search.SearchCriteriaMerger.copyOf;

import com.gen3.recommenderagent.application.conversation.intent.UnknownHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.session.Session;

/**
 * Resolves short replies and cancellation against the outstanding question before intent dispatch.
 */
public final class ClarificationResolver {
  private final HandlerRegistry handlers;

  public ClarificationResolver(HandlerRegistry handlers) {
    this.handlers = handlers;
  }

  /** Applies handler-owned reply rules before dispatching the effective intent. */
  public ConversationDraft resolve(Session session, SessionRequest input) {
    var effective = copyOf(input);
    ConversationRules.normalizeInput(effective, handlers);
    if (session == null) {
      return effective;
    }
    String reply = effective.getRawText() == null ? "" : effective.getRawText().strip();
    for (var handler : handlers.definitions()) {
      handler.preparePendingReply(session, effective, reply);
    }
    boolean contextual =
        handlers.definitions().stream()
            .anyMatch(handler -> handler.isContextualReply(session, effective, reply));
    if (handlers.handler(effective.getIntent()).bypassesClarification() && !contextual) {
      return effective;
    }
    // Cancellation must be recognized before a new action abandons the question.
    for (var handler : handlers.definitions()) {
      if (handler.handlesInterruptions()) {
        var resolved = handler.resolvePendingReply(session, effective, reply);
        if (resolved != null) {
          return normalizeResolved(resolved);
        }
      }
    }
    if (isNewAction(effective) && !contextual) {
      session.setPendingQuestion(null);
      return effective;
    }
    for (var handler : handlers.definitions()) {
      if (!handler.handlesInterruptions()) {
        var resolved = handler.resolvePendingReply(session, effective, reply);
        if (resolved != null) {
          return normalizeResolved(resolved);
        }
      }
    }
    if (handlers.handler(effective.getIntent()).questionPolicy()
            == ConversationHandler.QuestionPolicy.ATTACH
        && session.getPendingQuestion() != null) {
      attachQuestion(session, effective);
    } else {
      clearAbandonedQuestions(session, effective);
    }
    return effective;
  }

  /** Normalizes fields again after a pending reply changes its effective intent. */
  private ConversationDraft normalizeResolved(ConversationDraft request) {
    ConversationRules.normalizeInput(request, handlers);
    return request;
  }

  /** Reports whether the incoming action abandons the previous pending question. */
  private boolean isNewAction(ConversationDraft request) {
    return handlers.handler(request.getIntent()).abandonsPendingQuestion(request);
  }

  /** Clears stale clarification state when the action no longer needs it. */
  private void clearAbandonedQuestions(Session session, ConversationDraft request) {
    if (request.getIntent() != UnknownHandler.ACTION
        && !session.isAwaitingPersonalisedPreferences()
        && !session.isAwaitingPreferenceUpdate()) {
      session.setPendingQuestion(null);
    }
  }

  /** Copies retained question context into the outgoing conversation draft. */
  private void attachQuestion(Session session, ConversationDraft request) {
    request.setPendingQuestion(session.getPendingQuestionKind());
    if (session.getPendingQuestion() == null) {
      return;
    }
    for (var handler : handlers.definitions()) {
      handler.attachQuestionContext(session, request);
    }
  }

  /** Persists the next pending question while preserving it for read-only and replay actions. */
  public void complete(Session session, ConversationDraft request) {
    ConversationAction intent = request.getIntent();
    var policy = handlers.handler(intent).questionPolicy();
    if (policy == ConversationHandler.QuestionPolicy.PRESERVE) {
      return;
    }
    if (policy == ConversationHandler.QuestionPolicy.ATTACH) {
      attachQuestion(session, request);
      return;
    }
    handlers.handler(intent).finishQuestion(request);
    var question = request.getPendingQuestion();
    if (question == null) {
      session.setPendingQuestion(null);
    } else {
      session.setPendingQuestionKind(question);
      handlers.questionHandler(question).retainQuestion(session, request);
    }
  }
}
