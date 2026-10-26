package com.gen3.recommenderagent.domain.conversation;

import static com.gen3.recommenderagent.domain.conversation.ConversationRules.normalizeInput;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;

/** Applies profile edits without disturbing the current recommendation batch. */
public final class PreferenceConversationHandler implements ConversationHandler {
  public java.util.Set<Intent> intents() {
    return java.util.Set.of(Intent.UPDATE_PREFERENCES);
  }

  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (!effective.getPreferenceRemovalTerms().isEmpty()) {
      var preferences =
          session.getPreferences() == null ? new SessionPreferences() : session.getPreferences();
      effective.setPreferenceUpdates(
          preferences.withoutTerms(
              effective.getPreferenceRemovalTerms(), effective.isPositivePreferenceRemovalOnly()));
      effective.setDecision(ConversationDecision.PREFERENCES_UPDATED);
      return effective;
    }
    SessionPreferences updates = effective.getPreferenceUpdates();
    if (updates == null) {
      updates = SessionPreferences.fromRequest(effective);
    }
    boolean changes =
        updates.hasValues()
            || (effective.getPreferenceRemovals() != null
                && effective.getPreferenceRemovals().hasValues())
            || (effective.getReplacePreferenceFields() != null
                && !effective.getReplacePreferenceFields().isEmpty());
    effective.setMustInclude(null);
    effective.setMustNotInclude(null);
    normalizeInput(effective);
    if (!changes) {
      effective.setDecision(ConversationDecision.PREFERENCE_UPDATE_CLARIFICATION);
    } else {
      effective.setPreferenceUpdates(
          session.updatedPreferences(
              updates, effective.getPreferenceRemovals(), effective.getReplacePreferenceFields()));
      effective.setDecision(ConversationDecision.PREFERENCES_UPDATED);
    }
    return effective;
  }

  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    boolean clarification =
        request.getDecision() == ConversationDecision.PREFERENCE_UPDATE_CLARIFICATION;
    session.setAwaitingPreferenceUpdate(clarification);
    if (!clarification) {
      session.setPreferences(request.getPreferenceUpdates().copy());
    }
    request.setRecommendations(List.of());
  }
}
