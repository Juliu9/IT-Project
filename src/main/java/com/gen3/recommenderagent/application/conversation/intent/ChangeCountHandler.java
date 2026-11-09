package com.gen3.recommenderagent.application.conversation.intent;

import static com.gen3.recommenderagent.domain.recommendation.RecommendationPolicy.MAX_BOOK_COUNT;
import static com.gen3.recommenderagent.inputparser.CommandValues.COUNT;
import static com.gen3.recommenderagent.inputparser.CommandValues.countValue;
import static com.gen3.recommenderagent.inputparser.CommandValues.intent;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.CountInput;
import com.gen3.recommenderagent.inputparser.UserIntent;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(15)
public final class ChangeCountHandler implements ConversationHandler {
  public static final ConversationAction ACTION = ConversationAction.of("CHANGE_COUNT");

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      CHANGE_COUNT=the persistent result-count setting.
      """;
  }

  @Override
  public ConversationDraft resolve(Session session, ConversationDraft input) {
    if (input.isInvalidBookCount() || input.getBookCount() == null || input.getBookCount() <= 0) {
      input.setInvalidBookCount(true);
      input.setBookCount(session.getBookCount());
    }
    return input;
  }

  @Override
  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    request.setRecommendations(List.of());
    if (!request.isInvalidBookCount() && request.getBookCount() != null) {
      session.setBookCount(request.getBookCount());
    }
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
    return turn.isInvalidBookCount()
        ? "Recommendation counts must be whole numbers between 1 and "
            + MAX_BOOK_COUNT
            + ". Please try asking again with a number in this range. Your setting is still "
            + turn.getBookCount()
            + "."
        : "I'll show "
            + Math.clamp(turn.getBookCount(), 1, MAX_BOOK_COUNT)
            + " recommendation"
            + (turn.getBookCount() == 1 ? "" : "s")
            + " at a time.";
  }

  private static final Pattern CHANGE_COUNT =
      Pattern.compile(
          "(?:(?:change|set)(?: the| my)? (?:recommendation )?count to ("
              + COUNT
              + ")(?: books?| audiobooks?| recommendations?)?(?: at a time| per request| per page)?"
              + "|(?:give me |show me |i want |i'd like |i would like )?(?:only )?("
              + COUNT
              + ")(?: books?| audiobooks?| recommendations?)? (?:at a time|per request|per page))");

  @Override
  public UserIntent recognize(String command) {
    var exact = commands().contains(command);
    if (exact) {
      return intent(intents().iterator().next());
    }
    var changedCountResult = changedCount(command);
    if (changedCountResult != null) {
      return changedCountResult;
    }
    return null;
  }

  /** Recognizes an explicit change to the saved recommendation count. */
  private UserIntent changedCount(String command) {
    var changedCount = CHANGE_COUNT.matcher(command);
    if (changedCount.matches()) {
      var parsed = intent(ACTION);
      parsed.setBookCount(
          countValue(
              changedCount.group(1) == null ? changedCount.group(2) : changedCount.group(1)));
      return parsed;
    }
    return null;
  }

  @Override
  public boolean recognizesInvalidCount(String rawText, String modelText) {
    return CountInput.setting(rawText) || modelText.equals("1");
  }

  @Override
  public boolean suppressesCountNotice() {
    return true;
  }
}
