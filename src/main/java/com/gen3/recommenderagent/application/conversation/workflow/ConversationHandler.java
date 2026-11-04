package com.gen3.recommenderagent.application.conversation.workflow;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.Set;

/** One family of session actions. Implementations mutate only the supplied transaction state. */
public interface ConversationHandler {
  /** Returns the conversation actions handled by this handler. */
  Set<ConversationAction> intents();

  /** Resolves this action family against the supplied mutable session state. */
  ConversationDraft resolve(Session session, ConversationDraft input);

  /** Applies completion changes after validation and any required recommendation retrieval. */
  default void finish(Session session, ConversationDraft result, SessionRequest original) {}

  /** Reports whether the resolved action requires a recommendation page. */
  default boolean needsRecommendations(ConversationDraft result) {
    return false;
  }

  /** Reports whether the incoming action makes the cached recommendation queue obsolete. */
  default boolean invalidatesBatch(SessionRequest original) {
    return false;
  }

  /** Reports whether this handler retains reusable search context after completion. */
  default boolean retainsHistory() {
    return false;
  }

  /** Returns the telemetry label describing how this action obtained its results. */
  default String retrievalMode(ConversationDraft result) {
    return null;
  }

  /** Reports whether the completed result should replace the repeatable last outcome. */
  default boolean remembersTurn(ConversationDraft result) {
    return true;
  }
}
