package com.gen3.recommenderagent.application.conversation.intent;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class HelpHandler implements ConversationHandler {
  public static final ConversationAction ACTION = ConversationAction.of("HELP");

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      HELP=capability, supported-request, or usage questions, including "explain how it works".
      """;
  }

  @Override
  public Set<String> commands() {
    return Set.of(
        "help",
        "show help",
        "what can you do",
        "which operations do you support",
        "what operations do you support",
        "what else can you do",
        "what do you do",
        "how do i use this",
        "how does this work",
        "what can i ask");
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
    return "I can help you find audiobooks, adjust your search, or explore more books. You can ask me to "
        + "repeat my last reply or the last book list, explain a recommendation, or change how many books I show. "
        + "You can also view, update or reset your preferences, cancel a request, or clear this session's history.";
  }

  @Override
  public QuestionPolicy questionPolicy() {
    return QuestionPolicy.ATTACH;
  }

  @Override
  public boolean includesPendingQuestion(ConversationTurn turn) {
    return turn.getPendingQuestion()
            != ExplainRecommendationHandler.EXPLANATION_TARGET_CLARIFICATION
        || turn.getExplanationSelection() != null;
  }
}
