package com.gen3.recommenderagent.application.conversation.intent;

import static com.gen3.recommenderagent.application.conversation.ConversationHistory.latestDisplayedSearch;
import static com.gen3.recommenderagent.application.conversation.ConversationHistory.restoreRequest;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class RepeatRecommendationsHandler implements ConversationHandler {
  public static final ConversationAction ACTION = ConversationAction.of("REPEAT_RECOMMENDATIONS");
  public static final ConversationOutcome NO_RECOMMENDATIONS_TO_REPEAT =
      ConversationOutcome.of("NO_RECOMMENDATIONS_TO_REPEAT");

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      REPEAT_RECOMMENDATIONS=the same previous book list; a same-list request takes precedence over REPEAT.
      """;
  }

  @Override
  public Set<String> commands() {
    return Set.of(
        "repeat the books",
        "repeat the recommendations",
        "repeat recommendations",
        "repeat the audiobooks",
        "repeat those books",
        "repeat those recommendations",
        "show those recommendations again",
        "show me the recommendations again",
        "show those books again",
        "show me the same books again",
        "show me the same audiobook list you gave me earlier");
  }

  @Override
  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (session == null) {
      return effective;
    }
    ConversationDraft previous = latestDisplayedSearch(session);
    ConversationDraft repeated = restoreRequest(previous, effective);
    repeated.setPendingQuestion(null);
    repeated.setPreferenceClarificationRequired(false);
    repeated.setSearchClarificationRequired(false);
    repeated.setNoMoreRecommendations(
        previous != null
            && (previous.isNoMoreRecommendations()
                || previous.getIntent() == MoreResultsHandler.ACTION)
            && repeated.getRecommendations().isEmpty());
    if (previous == null) {
      repeated.setOutcome(NO_RECOMMENDATIONS_TO_REPEAT);
    }
    return repeated;
  }

  @Override
  public String retrievalMode(ConversationDraft result) {
    return "SESSION_REPEAT_RECOMMENDATIONS";
  }

  @Override
  public String outcomeResponse(ConversationTurn turn) {
    if (turn.getOutcome() == null) {
      return null;
    }
    if (turn.getOutcome() == NO_RECOMMENDATIONS_TO_REPEAT) {
      return emptyResponse(turn);
    }
    return null;
  }

  @Override
  public String emptyResponse(com.gen3.recommenderagent.domain.conversation.ConversationTurn turn) {
    return "I don't have a list of recommendations to repeat right now. What kind of audiobook would you like?";
  }

  @Override
  public Set<ConversationOutcome> outcomes() {
    return Set.of(NO_RECOMMENDATIONS_TO_REPEAT);
  }

  @Override
  public boolean emptyOutcome(ConversationOutcome outcome) {
    return outcome == NO_RECOMMENDATIONS_TO_REPEAT;
  }
}
