package com.gen3.recommenderagent.domain.session;

/** Pending reference criteria and decision; the request snapshot already owns the choices. */
public record PendingSimilarBook(ConversationTurn request, ConversationDecision decision) {}
