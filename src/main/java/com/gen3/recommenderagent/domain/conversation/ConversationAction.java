package com.gen3.recommenderagent.domain.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.concurrent.ConcurrentHashMap;

/** Stable identity of an intent declared by a handler. */
public final class ConversationAction {
  private static final ConcurrentHashMap<String, ConversationAction> IDENTIFIERS =
      new ConcurrentHashMap<>();
  private final String name;

  private ConversationAction(String name) {
    this.name = name;
  }

  /** Declares or returns a canonical identifier without a central registration list. */
  public static ConversationAction of(CharSequence identifier) {
    String name = identifier == null ? null : identifier.toString();
    if (name == null || !name.matches("[A-Z][A-Z0-9_]*")) {
      throw new IllegalArgumentException(
          "Identifiers must use uppercase letters, digits and underscores");
    }
    return IDENTIFIERS.computeIfAbsent(name, ConversationAction::new);
  }

  /** Restores an identifier declared by a discovered handler. */
  @JsonCreator
  public static ConversationAction valueOf(String name) {
    var identifier = IDENTIFIERS.get(name);
    if (identifier == null) {
      throw new IllegalArgumentException("Unknown ConversationAction: " + name);
    }
    return identifier;
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
