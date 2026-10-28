package com.gen3.recommenderagent.application.conversation.workflow;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import com.gen3.recommenderagent.domain.session.Session;

/** Interprets a reply to one persisted kind of unfinished question. */
interface PendingQuestionHandler<T extends PendingQuestion> {
  Class<T> questionType();

  default boolean isContextualReply(Session session, ConversationDraft input, String reply) {
    return false;
  }

  ConversationDraft resolve(Session session, ConversationDraft input, String reply);
}
