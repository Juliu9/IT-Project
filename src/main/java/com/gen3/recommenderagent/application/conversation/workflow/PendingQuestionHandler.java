package com.gen3.recommenderagent.application.conversation.workflow;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import com.gen3.recommenderagent.domain.session.Session;

/** Interprets a reply to one persisted kind of unfinished question. */
interface PendingQuestionHandler<T extends PendingQuestion> {
  /** Returns the persisted pending-question subtype understood by this handler. */
  Class<T> questionType();

  /** Checks whether the reply answers the current question rather than starting another action. */
  default boolean isContextualReply(Session session, ConversationDraft input, String reply) {
    return false;
  }

  /** Interprets the reply using the context owned by this pending-question type. */
  ConversationDraft resolve(Session session, ConversationDraft input, String reply);
}
