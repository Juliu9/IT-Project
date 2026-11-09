package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.application.conversation.HandlerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Applies shared wording normalization before invoking handler-owned complete-command recognition.
 */
@Component
public class CommandParser {
  private final HandlerRegistry handlers;

  @Autowired
  public CommandParser(HandlerRegistry handlers) {
    this.handlers = handlers;
  }

  /** Resolves supported full commands without invoking the AI provider. */
  public UserIntent parse(String rawText) {
    String command = ConversationText.normalizeCommand(rawText);
    return handlers.recognize(command);
  }
}
