package com.gen3.recommenderagent.application.conversation.intent;

import static com.gen3.recommenderagent.application.conversation.ConversationHistory.latestDisplayedSearch;
import static com.gen3.recommenderagent.application.conversation.ConversationHistory.restoreRequest;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class RepeatHandler implements ConversationHandler {
  public static final ConversationAction ACTION = ConversationAction.of("REPEAT");
  public static final ConversationOutcome NOTHING_TO_REPEAT =
      ConversationOutcome.of("NOTHING_TO_REPEAT");

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      REPEAT=the latest action/answer. "repeat your last explanation" is REPEAT.
      """;
  }

  @Override
  public Set<String> commands() {
    return Set.of(
        "repeat",
        "repeat that",
        "say that again",
        "say it again",
        "repeat yourself",
        "repeat your last response",
        "repeat the last response",
        "repeat your last answer");
  }

  @Override
  public QuestionPolicy questionPolicy() {
    return QuestionPolicy.PRESERVE;
  }

  @Override
  public ConversationDraft resolve(Session session, ConversationDraft input) {
    return session == null ? input : resolveRepeat(session, input);
  }

  @Override
  public boolean remembersTurn(ConversationDraft result) {
    return false;
  }

  @Override
  public String retrievalMode(ConversationDraft result) {
    return "SESSION_REPEAT";
  }

  /** Restores the most recent repeatable outcome or reports that none exists. */
  private ConversationDraft resolveRepeat(Session session, ConversationDraft incoming) {
    var outcome = session.getLastOutcome();
    ConversationDraft previous =
        outcome == null || outcome.turn() == null
            ? latestDisplayedSearch(session)
            : ConversationDraft.fromTurn(outcome.turn());
    var savedOutcome = outcome == null ? null : outcome.outcome();
    if (previous == null) {
      incoming.setRecommendations(List.of());
      incoming.setOutcome(NOTHING_TO_REPEAT);
      return incoming;
    }
    ConversationDraft repeated = restoreRequest(previous, incoming);
    repeated.setOutcome(savedOutcome);
    repeated.setResponseAction(previous.getIntent());
    return repeated;
  }

  @Override
  public String outcomeResponse(ConversationTurn turn) {
    if (turn.getOutcome() == null) {
      return null;
    }
    if (turn.getOutcome() == NOTHING_TO_REPEAT) {
      return emptyResponse(turn);
    }
    return null;
  }

  @Override
  public String emptyResponse(com.gen3.recommenderagent.domain.conversation.ConversationTurn turn) {
    return "I don't have an earlier request to repeat yet. Would you like recommendations or help getting started?";
  }

  @Override
  public String fallbackResponse(ConversationTurn turn, int labelWords) {
    if (turn.isPersonalised()) {
      return RecommendationHandler.personalisedNoMatches();
    }
    return "I couldn't find any audiobooks matching those search criteria. Would you like to try a different genre, author, narrator, or description?";
  }

  @Override
  public Set<ConversationOutcome> outcomes() {
    return Set.of(NOTHING_TO_REPEAT);
  }

  @Override
  public boolean emptyOutcome(ConversationOutcome outcome) {
    return outcome == NOTHING_TO_REPEAT;
  }
}
