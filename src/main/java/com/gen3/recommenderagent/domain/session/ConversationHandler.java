package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.domain.Intent;
import java.util.Set;

/** One family of session actions. Implementations mutate only the supplied transaction state. */
public interface ConversationHandler {
  Set<Intent> intents();

  ConversationDraft resolve(Session session, ConversationDraft input);

  default void finish(Session session, ConversationDraft result, SessionRequest original) {}

  default boolean needsRecommendations(ConversationDraft result) {
    return false;
  }

  default boolean invalidatesBatch(SessionRequest original) {
    return false;
  }

  default boolean retainsHistory() {
    return false;
  }

  default String retrievalMode(ConversationDraft result) {
    return null;
  }

  default boolean remembersTurn(ConversationDraft result) {
    return true;
  }
}
