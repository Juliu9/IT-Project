package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.inputparser.UserIntent;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared lookup used by parsing, execution, and rendering; all intent policies come from handlers.
 */
public final class HandlerRegistry {
  private final Map<ConversationAction, ConversationHandler> handlers = new LinkedHashMap<>();
  private final List<ConversationHandler> definitions;

  public HandlerRegistry(Collection<ConversationHandler> definitions) {
    for (var handler : definitions) {
      for (var intent : handler.intents()) {
        if (handlers.putIfAbsent(intent, handler) != null) {
          throw new IllegalArgumentException("Duplicate conversation handler: " + intent);
        }
      }
    }
    this.definitions = List.copyOf(new LinkedHashSet<>(handlers.values()));
    var outcomeOwners = new java.util.HashMap<ConversationOutcome, ConversationHandler>();
    var commandOwners = new java.util.HashMap<String, ConversationHandler>();
    var questionOwners = new java.util.HashMap<ConversationQuestion, ConversationHandler>();
    for (var definition : this.definitions) {
      for (var outcome : definition.outcomes()) {
        if (outcomeOwners.putIfAbsent(outcome, definition) != null) {
          throw new IllegalArgumentException("Duplicate outcome owner: " + outcome);
        }
      }
      for (var question : definition.questions()) {
        if (questionOwners.putIfAbsent(question, definition) != null) {
          throw new IllegalArgumentException("Duplicate question owner: " + question);
        }
      }
      for (var command : definition.commands()) {
        if (commandOwners.putIfAbsent(command, definition) != null) {
          throw new IllegalArgumentException("Duplicate intent command: " + command);
        }
      }
    }
  }

  public ConversationHandler handler(ConversationAction intent) {
    var handler = handlers.get(intent);
    if (handler == null) {
      throw new IllegalArgumentException("No handler for intent: " + intent);
    }
    return handler;
  }

  /** Returns only outcomes declared by this application's discovered handlers. */
  public Set<ConversationOutcome> outcomes() {
    return definitions.stream()
        .flatMap(handler -> handler.outcomes().stream())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /** Returns only questions declared by this application's discovered handlers. */
  public Set<ConversationQuestion> questions() {
    return definitions.stream()
        .flatMap(handler -> handler.questions().stream())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public Set<ConversationAction> actions() {
    return Collections.unmodifiableSet(handlers.keySet());
  }

  /** Returns handlers in recognition and pending-reply priority order. */
  public java.util.List<ConversationHandler> definitions() {
    return definitions;
  }

  public UserIntent recognize(String command) {
    for (var handler : definitions) {
      var parsed = handler.recognize(command);
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  public com.gen3.recommenderagent.domain.conversation.ConversationTurn recoverFailure(
      com.gen3.recommenderagent.domain.session.Session session,
      ConversationAction action,
      com.gen3.recommenderagent.domain.session.PendingQuestion pending,
      RuntimeException exception) {
    for (var handler : definitions) {
      var recovered = handler.recoverFailure(session, action, pending, exception);
      if (recovered != null) {
        return recovered;
      }
    }
    throw exception;
  }

  public String outcomeResponse(
      com.gen3.recommenderagent.domain.conversation.ConversationTurn turn) {
    return turn.getOutcome() == null
        ? null
        : outcomeHandler(turn.getOutcome()).outcomeResponse(turn);
  }

  /** Resolves a completed result using its declared owner. */
  public ConversationHandler outcomeHandler(ConversationOutcome outcome) {
    return definitions.stream()
        .filter(handler -> handler.outcomes().contains(outcome))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No handler for outcome: " + outcome));
  }

  /** Gives handlers ownership of exhausted-search wording, including replay. */
  public String exhaustedResponse(
      com.gen3.recommenderagent.domain.conversation.ConversationTurn turn) {
    for (var handler : definitions) {
      var response = handler.exhaustedResponse(turn);
      if (response != null) {
        return response;
      }
    }
    return null;
  }

  /** Finds the single handler owning a question, including questions attached to other intents. */
  public ConversationHandler questionHandler(ConversationQuestion question) {
    return definitions().stream()
        .filter(handler -> handler.questions().contains(question))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No handler for question: " + question));
  }

  public String instructions() {
    return handlers.entrySet().stream()
        .map(
            entry ->
                entry.getKey().name()
                    + "="
                    + entry.getValue().instructions()
                    + "\nAccepts top-level search semantics: "
                    + entry.getValue().acceptsSearchSemantics())
        .collect(java.util.stream.Collectors.joining("\n"));
  }
}
