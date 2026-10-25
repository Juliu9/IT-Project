package com.gen3.recommenderagent.domain.conversation;

import static com.gen3.recommenderagent.domain.conversation.ConversationRules.normalizeInput;
import static com.gen3.recommenderagent.domain.conversation.SearchCriteriaMerger.copyOf;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resolves short replies and cancellation against the outstanding question before intent dispatch.
 */
public final class ClarificationResolver {
  private static final Pattern SELECTION_REPLY =
      Pattern.compile(
          "(?i)^(?:the\\s+)?(?:(?:book|audiobook)\\s+)?(?:number\\s+)?([0-9]+(?:st|nd|rd|th)?|one|two|three|four|five|six|seven|eight|nine|ten|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)(?:\\s+(?:one|book|audiobook))?(?:\\s+please)?[.!?]?$");

  public ConversationDraft resolve(Session session, SessionRequest input) {
    ConversationDraft effective = copyOf(input);
    if (session == null) {
      return effective;
    }
    String reply = effective.getRawText() == null ? "" : effective.getRawText().strip();
    if (reply.matches("(?i)(?:never mind|nevermind|cancel|stop|forget it)[.!?]?")) {
      effective.setIntent(Intent.CANCEL);
    }
    if (effective.getIntent() == Intent.CANCEL) {
      normalizeInput(effective);
      return effective;
    }
    if (effective.getIntent() == Intent.UPDATE_PREFERENCES
        || (session.isAwaitingPreferenceUpdate()
            && session.getLastDecision() != ConversationDecision.EXPLANATION_TARGET_CLARIFICATION
            && session.getLastDecision() != ConversationDecision.COUNT_CLARIFICATION
            && !effective.isExplicitSearch()
            && (effective.getIntent() == Intent.RECOMMENDATION
                || effective.getIntent() == Intent.REFINE
                || effective.getIntent() == Intent.UNKNOWN))) {
      effective.setIntent(Intent.UPDATE_PREFERENCES);
      if (effective.getPreferenceUpdates() == null) {
        effective.setPreferenceUpdates(SessionPreferences.fromRequest(effective));
      }
      return effective;
    }
    // The parser has no session context; resolve short answers against the outstanding question.
    Integer selection = selectionReply(reply);
    if (session.getLastDecision() == ConversationDecision.COUNT_CLARIFICATION
        && selection != null) {
      effective.setIntent(Intent.CHANGE_COUNT);
      normalizeInput(effective);
      effective.setBookCount(selection);
    }
    if (session.getLastDecision() == ConversationDecision.EXPLANATION_TARGET_CLARIFICATION
        && (selection != null
            || (!effective.isExplicitSearch()
                && !reply.isBlank()
                && (effective.getIntent() == Intent.UNKNOWN
                    || ((effective.getIntent() == Intent.RECOMMENDATION
                            || effective.getIntent() == Intent.REFINE)
                        && ExplanationConversationHandler.matchesDisplayedTitle(
                            session, reply)))))) {
      effective.setIntent(Intent.EXPLAIN_RECOMMENDATION);
      normalizeInput(effective);
      effective.setReferenceRank(selection);
      effective.setReferenceTitle(selection == null ? reply : null);
    }
    return effective;
  }

  private Integer selectionReply(String reply) {
    var match = SELECTION_REPLY.matcher(reply);
    if (!match.matches()) {
      return null;
    }
    return switch (match.group(1).toLowerCase(Locale.ROOT)) {
      case "one", "first" -> 1;
      case "two", "second" -> 2;
      case "three", "third" -> 3;
      case "four", "fourth" -> 4;
      case "five", "fifth" -> 5;
      case "six", "sixth" -> 6;
      case "seven", "seventh" -> 7;
      case "eight", "eighth" -> 8;
      case "nine", "ninth" -> 9;
      case "ten", "tenth" -> 10;
      default -> {
        try {
          yield Integer.valueOf(match.group(1).replaceFirst("(?i)(st|nd|rd|th)$", ""));
        } catch (NumberFormatException ignored) {
          yield Integer.MAX_VALUE;
        }
      }
    };
  }
}
