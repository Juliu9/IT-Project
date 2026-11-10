package com.gen3.recommenderagent.application.conversation.intent;

import static com.gen3.recommenderagent.inputparser.CommandValues.intent;
import static com.gen3.recommenderagent.inputparser.ConversationText.selectionReply;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.ConversationText;
import com.gen3.recommenderagent.inputparser.UserIntent;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(14)
public final class UnknownHandler implements ConversationHandler {
  public static final ConversationAction ACTION = ConversationAction.of("UNKNOWN");

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      UNKNOWN=unsupported or unclear input.
      """;
  }

  @Override
  public ConversationDraft resolve(Session session, ConversationDraft input) {
    return input;
  }

  @Override
  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    request.setRecommendations(List.of());
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
    return "I'm not quite sure what you'd like to do. You can ask for audiobook recommendations or "
        + "say help to hear what I can do.";
  }

  @Override
  public QuestionPolicy questionPolicy() {
    return QuestionPolicy.ATTACH;
  }

  @Override
  public UserIntent recognize(String command) {
    var exact = commands().contains(command);
    if (exact) {
      return intent(intents().iterator().next());
    }
    return isContextualReply(command) ? intent(ACTION) : null;
  }

  /** Identifies short wording that may answer an outstanding conversation question. */
  private boolean isContextualReply(String rawText) {
    return ConversationText.replacementReferenceTitle(rawText) != null
        || ConversationText.relativeSelectionReply(rawText, 2) != null
        || ConversationText.isChoiceRejection(rawText)
        || ConversationText.isWholePageReply(rawText)
        || ConversationText.isAffirmativeReply(rawText)
        || selectionReply(rawText.strip()) != null
        || com.gen3.recommenderagent.inputparser.ConversationText.isPreferenceDecline(rawText);
  }

  @Override
  public String withQuestion(String response, String question) {
    return "I didn't quite catch that. Just to clarify, " + question;
  }

  @Override
  public boolean bypassesClarification() {
    return false;
  }
}
