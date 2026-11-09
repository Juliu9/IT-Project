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
public final class ClearHistoryHandler implements ConversationHandler {
  public static final ConversationAction ACTION = ConversationAction.of("CLEAR_HISTORY");

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      CLEAR_HISTORY=clear recommendation history.
      """;
  }

  @Override
  public Set<String> commands() {
    return Set.of(
        "clear",
        "clear history",
        "clear my history",
        "clear recommendation history",
        "clear my recommendation history");
  }

  @Override
  public ConversationDraft resolve(Session session, ConversationDraft input) {
    return input;
  }

  @Override
  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    request.setRecommendations(List.of());
    session.clearHistory();
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
    return "All set, I've cleared this session's recommendation history.";
  }
}
