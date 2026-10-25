package com.gen3.recommenderagent.domain.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Pending reference criteria and decision; the request snapshot already owns the choices. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PendingSimilarBook(ConversationTurn request, ConversationDecision decision) {}
