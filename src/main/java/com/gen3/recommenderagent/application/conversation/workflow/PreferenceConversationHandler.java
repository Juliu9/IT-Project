package com.gen3.recommenderagent.application.conversation.workflow;

import static com.gen3.recommenderagent.application.conversation.workflow.ConversationRules.normalizeInput;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.PreferenceRequestMapper;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import java.util.List;

/** Applies profile edits without disturbing the current recommendation batch. */
public final class PreferenceConversationHandler implements ConversationHandler {
  public java.util.Set<ConversationAction> intents() {
    return java.util.Set.of(ConversationAction.UPDATE_PREFERENCES);
  }

  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (!effective.getPreferenceRemovalTerms().isEmpty()) {
      var preferences =
          session.getPreferences() == null ? new SessionPreferences() : session.getPreferences();
      effective.setPreferenceUpdates(
          preferences.withoutTerms(
              effective.getPreferenceRemovalTerms(), effective.isPositivePreferenceRemovalOnly()));
      return effective;
    }
    SessionPreferences updates = effective.getPreferenceUpdates();
    if (updates == null) {
      updates = PreferenceRequestMapper.fromRequest(effective);
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
      effective.setPendingQuestion(ConversationQuestion.PREFERENCE_UPDATE_CLARIFICATION);
    } else {
      effective.setPreferenceUpdates(
          session.updatedPreferences(
              updates, effective.getPreferenceRemovals(), effective.getReplacePreferenceFields()));
    }
    return effective;
  }

  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    boolean clarification =
        request.getPendingQuestion() == ConversationQuestion.PREFERENCE_UPDATE_CLARIFICATION;
    if (!clarification) {
      session.setPreferences(request.getPreferenceUpdates().copy());
    }
    request.setRecommendations(List.of());
  }
}
