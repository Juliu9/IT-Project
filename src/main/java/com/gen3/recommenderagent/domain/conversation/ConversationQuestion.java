package com.gen3.recommenderagent.domain.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** A handler-declared question with the saved context needed for its next reply. */
public final class ConversationQuestion {
  public enum Context {
    SEARCH,
    PREFERENCE,
    PREFERENCE_UPDATE,
    EXPLANATION,
    SIMILAR_BOOK
  }

  private static final ConcurrentHashMap<String, ConversationQuestion> QUESTIONS =
      new ConcurrentHashMap<>();
  private final String name;
  private final Context context;
  private final Supplier<? extends PendingQuestion> factory;

  private ConversationQuestion(
      String name, Context context, Supplier<? extends PendingQuestion> factory) {
    this.name = name;
    this.context = context;
    this.factory = factory;
  }

  /** Declares the question and its reusable saved-context shape in its owning handler. */
  public static ConversationQuestion of(
      String name, Context context, Supplier<? extends PendingQuestion> factory) {
    if (name == null || !name.matches("[A-Z][A-Z0-9_]*")) {
      throw new IllegalArgumentException("Invalid question identifier: " + name);
    }
    java.util.Objects.requireNonNull(context);
    java.util.Objects.requireNonNull(factory);
    var question =
        QUESTIONS.computeIfAbsent(name, key -> new ConversationQuestion(key, context, factory));
    if (question.context != context) {
      throw new IllegalArgumentException("Conflicting question context: " + name);
    }
    return question;
  }

  /** Restores a question declared by a discovered handler. */
  @JsonCreator
  public static ConversationQuestion valueOf(String name) {
    var question = QUESTIONS.get(name);
    if (question == null) {
      throw new IllegalArgumentException("Unknown question: " + name);
    }
    return question;
  }

  /** Creates an empty saved context using the owner's factory. */
  public PendingQuestion createPending() {
    var question = factory.get();
    question.setKind(this);
    return question;
  }

  public Context context() {
    return context;
  }

  @JsonValue
  public String name() {
    return name;
  }

  @Override
  public String toString() {
    return name;
  }
}
