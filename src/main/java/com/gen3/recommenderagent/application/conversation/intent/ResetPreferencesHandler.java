package com.gen3.recommenderagent.application.conversation.intent;

import static com.gen3.recommenderagent.domain.recommendation.RecommendationPolicy.MAX_BOOK_COUNT;

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
public final class ResetPreferencesHandler implements ConversationHandler {
  public static final ConversationAction ACTION = ConversationAction.of("RESET_PREFERENCES");

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      RESET_PREFERENCES=reset all session preferences and the recommendation count. Clearing one preference field is UPDATE_PREFERENCES.
      """;
  }

  @Override
  public Set<String> commands() {
    return Set.of(
        "reset",
        "reset preferences",
        "reset my preferences",
        "reset session preferences",
        "clear preferences",
        "clear my preferences",
        "reset my tastes",
        "reset preferences and count");
  }

  @Override
  public ConversationDraft resolve(Session session, ConversationDraft input) {
    return input;
  }

  @Override
  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    request.setRecommendations(List.of());
    session.setPreferences(null);
    session.setBookCount(null);
    request.setBookCount(session.getBookCount());
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
    return "All set, I've reset your preferences for this session and will show "
        + MAX_BOOK_COUNT
        + " recommendations at a time.";
  }
}
