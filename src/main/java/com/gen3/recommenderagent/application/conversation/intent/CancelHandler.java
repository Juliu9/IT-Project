package com.gen3.recommenderagent.application.conversation.intent;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.ConversationText;
import com.gen3.recommenderagent.inputparser.UserIntent;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(0)
public final class CancelHandler implements ConversationHandler {
  public static final ConversationAction ACTION = ConversationAction.of("CANCEL");
  public static final ConversationOutcome SEARCH_DECLINED =
      ConversationOutcome.of("SEARCH_DECLINED");

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      CANCEL=abandon a pending action.
      """;
  }

  @Override
  public UserIntent recognize(String command) {
    if (!ConversationText.isCancellationReply(command)) {
      return null;
    }
    var parsed = new UserIntent();
    parsed.setIntent(ACTION);
    return parsed;
  }

  @Override
  public ConversationDraft resolve(Session session, ConversationDraft input) {
    return input;
  }

  @Override
  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    request.setRecommendations(List.of());
    session.setPendingQuestion(null);
    session.clearPreferenceQuestions();
  }

  @Override
  public boolean retainsHistory() {
    return true;
  }

  @Override
  public boolean narratesBooks() {
    return false;
  }

  @Override
  public String response(ConversationTurn turn) {
    if (turn.getOutcome() != null) {
      return null;
    }
    return "No problem, I've cancelled that. What would you like to do next?";
  }

  @Override
  public String outcomeResponse(ConversationTurn turn) {
    if (turn.getOutcome() == null) {
      return null;
    }
    if (turn.getOutcome() == SEARCH_DECLINED) {
      return "No problem. Let me know when you’d like to try another search.";
    }
    return null;
  }

  /** Recognizes cancellation and preference declines against the current unfinished action. */
  private ConversationDraft resolveCancellation(
      Session session, ConversationDraft effective, String reply) {
    if ((session.getPendingQuestionKind() == RecommendationHandler.SEARCH_CHANGE_OFFER)
        && ConversationText.isPreferenceDecline(reply)) {
      effective.setIntent(ACTION);
      com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput(
          effective, this);
      effective.setOutcome(SEARCH_DECLINED);
      return effective;
    }
    if (ConversationText.isCancellationReply(reply)
        || (session.getPendingQuestionKind() != null
            && ConversationText.normalizeCommand(reply).equals("no"))) {
      effective.setIntent(ACTION);
    }
    if ((session.getPendingQuestionKind() == RecommendationHandler.PREFERENCE_CLARIFICATION
            || session.getPendingQuestionKind()
                == UpdatePreferencesHandler.PREFERENCE_UPDATE_CLARIFICATION)
        && ConversationText.isPreferenceDecline(reply)) {
      effective.setIntent(ACTION);
    }
    if (effective.getIntent() == ACTION) {
      com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput(
          effective, this);
      return effective;
    }
    return null;
  }

  @Override
  public ConversationDraft resolvePendingReply(
      Session session, ConversationDraft input, String reply) {
    return resolveCancellation(session, input, reply);
  }

  @Override
  public boolean handlesInterruptions() {
    return true;
  }

  @Override
  public Set<ConversationOutcome> outcomes() {
    return Set.of(SEARCH_DECLINED);
  }
}
