package com.gen3.recommenderagent.domain.conversation;

import static com.gen3.recommenderagent.domain.conversation.ConversationRules.normalizeInput;
import static com.gen3.recommenderagent.domain.conversation.SearchCriteriaMerger.copyOf;

import com.gen3.catalogue.model.AudiobookTitle;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.domain.session.SimilarBookReference;
import java.util.List;
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
    // The parser has no session context; resolve short answers against the outstanding question.
    Integer selection = selectionReply(reply);
    if (session.getLastDecision() == ConversationDecision.COUNT_CLARIFICATION
        && selection != null) {
      effective.setIntent(Intent.CHANGE_COUNT);
      normalizeInput(effective);
      effective.setBookCount(selection);
      return effective;
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
      return effective;
    }
    boolean anotherQuestion =
        session.getLastDecision() != null
            && switch (session.getLastDecision()) {
              case COUNT_CLARIFICATION,
                  EXPLANATION_TARGET_CLARIFICATION,
                  PREFERENCE_CLARIFICATION,
                  PREFERENCE_UPDATE_CLARIFICATION,
                  SEARCH_CLARIFICATION ->
                  true;
              default -> false;
            };
    if (session.getPendingSimilarBook() != null && !anotherQuestion) {
      var resolved = resolveSimilarBook(session, effective, reply, selection);
      if (resolved != effective) {
        return resolved;
      }
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
    return effective;
  }

  private ConversationDraft resolveSimilarBook(
      Session session, ConversationDraft input, String reply, Integer selection) {
    var pending = session.getPendingSimilarBook();
    // A bare choice belongs to the pending question, regardless of model-inferred search flags.
    if (selection == null
        && (input.isExplicitSearch()
            || input.getSimilarToTitle() != null
            || input.isSimilarToClarificationRequired())) {
      return input;
    }
    var choices = pending.request().getSimilarBookChoices();
    if (selection == null
        && input.getIntent() != Intent.UNKNOWN
        && input.getIntent() != Intent.RECOMMENDATION
        && input.getIntent() != Intent.REFINE) {
      return input;
    }
    AudiobookTitle selected = null;
    if (selection != null && selection > 0 && selection <= choices.size()) {
      selected = choices.get(selection - 1);
    } else if (!reply.isBlank()) {
      var matches =
          choices.stream()
              .filter(
                  book ->
                      AudiobookTitle.normalize(book.title()).equals(AudiobookTitle.normalize(reply))
                          || book.authors().stream()
                              .anyMatch(
                                  author ->
                                      AudiobookTitle.normalize(author)
                                          .equals(AudiobookTitle.normalize(reply))))
              .toList();
      if (matches.size() == 1) {
        selected = matches.getFirst();
      }
    }
    if (selected == null) {
      if (!choices.isEmpty() && (selection != null || input.getIntent() == Intent.UNKNOWN)) {
        var again = pending.request().toDraft();
        again.setDecision(ConversationDecision.SIMILAR_BOOK_SELECTION);
        again.setSearchClarificationRequired(true);
        again.setSimilarBookChoices(choices);
        again.setRequestId(input.getRequestId());
        again.setRawText(input.getRawText());
        return again;
      }
      if (choices.isEmpty()
          && pending.decision() == ConversationDecision.SIMILAR_BOOK_REQUIRED
          && input.getIntent() == Intent.UNKNOWN
          && !reply.isBlank()) {
        var named = pending.request().toDraft();
        named.setRequestId(input.getRequestId());
        named.setRawText(input.getRawText());
        named.setCreatedAt(input.getCreatedAt());
        named.setSimilarToTitle(reply);
        named.setSearchClarificationRequired(false);
        named.setDecision(null);
        named.setExplicitSearch(true);
        return named;
      }
      if (choices.isEmpty()
          && pending.decision() == ConversationDecision.SIMILAR_BOOK_NOT_FOUND
          && (input.getIntent() == Intent.RECOMMENDATION || input.getIntent() == Intent.REFINE)) {
        var tastes = SearchCriteriaMerger.copyOf(input);
        SearchCriteriaMerger.merge(pending.request().toDraft(), tastes);
        tastes.setIntent(Intent.RECOMMENDATION);
        tastes.setExplicitSearch(true);
        session.setPendingSimilarBook(null);
        return tastes;
      }
      return input;
    }
    var resumed = pending.request().toDraft();
    resumed.setIntent(Intent.RECOMMENDATION);
    resumed.setExplicitSearch(true);
    resumed.setRequestId(input.getRequestId());
    resumed.setRawText(input.getRawText());
    resumed.setCreatedAt(input.getCreatedAt());
    resumed.setSearchClarificationRequired(false);
    resumed.setDecision(null);
    resumed.setSimilarBookChoices(List.of());
    resumed
        .getSearchContext()
        .setSimilarBook(
            new SimilarBookReference(
                selected.bookId(), selected.title(), selected.authors(), null));
    return resumed;
  }

  public static Integer selectionReply(String reply) {
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
