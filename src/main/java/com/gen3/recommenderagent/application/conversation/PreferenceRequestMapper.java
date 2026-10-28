package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.session.SessionPreferences;

/** Maps the application request shape to durable session preference values. */
public final class PreferenceRequestMapper {
  private PreferenceRequestMapper() {}

  public static SessionPreferences fromRequest(SessionRequest request) {
    var preferences = new SessionPreferences();
    preferences.setPositiveSemanticQuery(request.getPositiveSemanticQuery());
    preferences.setNegativeSemanticQuery(request.getNegativeSemanticQuery());
    preferences.setMustInclude(request.getMustInclude());
    preferences.setMustNotInclude(request.getMustNotInclude());
    return preferences.copy();
  }

  public static SessionRequest toRequest(SessionPreferences preferences) {
    var copy = preferences.copy();
    var request = new SessionRequest();
    request.setIntent(ConversationAction.RECOMMENDATION);
    request.setPositiveSemanticQuery(copy.getPositiveSemanticQuery());
    request.setNegativeSemanticQuery(copy.getNegativeSemanticQuery());
    request.setMustInclude(copy.getMustInclude());
    request.setMustNotInclude(copy.getMustNotInclude());
    return request;
  }
}
