package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;

/** The most recent completed result. REPEAT reads only this snapshot. */
public record LastOutcome(ConversationTurn turn, ConversationOutcome outcome) {}
