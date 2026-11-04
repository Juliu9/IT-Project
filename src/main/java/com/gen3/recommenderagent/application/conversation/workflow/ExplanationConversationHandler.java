package com.gen3.recommenderagent.application.conversation.workflow;

import static com.gen3.recommenderagent.application.conversation.workflow.ConversationHistory.latestDisplayedSearch;
import static com.gen3.recommenderagent.application.conversation.workflow.SearchCriteriaMerger.merge;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ExplanationSelection;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.ConversationText;
import com.gen3.shared.audiobookcatalogue.model.AudiobookTitle;
import java.util.List;

/** Resolves explanation targets exclusively against the displayed recommendation page. */
public final class ExplanationConversationHandler implements ConversationHandler {
  /** Returns the conversation actions handled by this handler. */
  public java.util.Set<ConversationAction> intents() {
    return java.util.Set.of(ConversationAction.EXPLAIN_RECOMMENDATION);
  }

  /** Resolves the requested explanation against the retained displayed recommendation page. */
  public ConversationDraft resolve(Session session, ConversationDraft input) {
    if (session == null) {
      input.setOutcome(ConversationOutcome.NOTHING_TO_EXPLAIN);
      return input;
    }
    resolveExplanation(input, session);
    return input;
  }

  /** Determines the explanation targets and any clarification needed for the current page. */
  private void resolveExplanation(ConversationDraft target, Session session) {
    target.setRecommendations(List.of());
    target.setPreferenceClarificationRequired(false);
    target.setMustInclude(null);
    target.setMustNotInclude(null);
    target.setBookCount(null);
    ConversationDraft previous = latestDisplayedSearch(session);
    if (previous == null) {
      target.setOutcome(ConversationOutcome.NOTHING_TO_EXPLAIN);
      return;
    }
    merge(previous, target);
    List<Recommendation> page =
        previous.getRecommendations() == null
            ? List.of()
            : previous.getRecommendations().stream()
                .sorted(java.util.Comparator.comparing(Recommendation::getRank))
                .toList();
    target.setRecommendations(page);
    if (page.isEmpty()) {
      target.setOutcome(ConversationOutcome.EMPTY_EXPLANATION_PAGE);
      return;
    }
    if (target.getExplanationSelection() != null) {
      resolveSubset(target, session, page);
      return;
    }
    session.setPendingExplanation(null);
    Integer rank = target.getReferenceRank();
    String title = AudiobookTitle.normalize(target.getReferenceTitle());
    List<Recommendation> matches = page;
    if (!title.isEmpty()) {
      matches = matchingTitles(page, title);
    }
    // Resolve a title independently before applying rank; an ambiguous title needs clarification.
    if ((title.isEmpty()
            && target.getReferenceTitle() != null
            && !target.getReferenceTitle().isBlank())
        || (!title.isEmpty() && matches.size() != 1)
        || (rank != null && matches.stream().noneMatch(book -> rank.equals(book.getRank())))) {
      target.setPendingQuestion(ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION);
      return;
    }
    if (rank != null) {
      matches = matches.stream().filter(book -> rank.equals(book.getRank())).toList();
    }
    target.setRecommendations(matches);
  }

  /** Restricts explanation to the selected recommendations from the displayed page. */
  private void resolveSubset(ConversationDraft target, Session session, List<Recommendation> page) {
    var selection = target.getExplanationSelection();
    var include = resolveSelectors(selection.include(), page);
    var exclude = resolveSelectors(selection.exclude(), page);
    var normalized = new ExplanationSelection(include, exclude);
    target.setExplanationSelection(normalized);
    if (firstUnresolved(normalized, page) != null) {
      session.setPendingExplanation(normalized);
      target.setPendingQuestion(ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION);
      return;
    }
    var included = include.stream().map(Integer::valueOf).toList();
    var excluded = exclude.stream().map(Integer::valueOf).toList();
    var selected =
        page.stream()
            .filter(
                book ->
                    (include.isEmpty() || included.contains(book.getRank()))
                        && !excluded.contains(book.getRank()))
            .toList();
    session.setPendingExplanation(null);
    target.setRecommendations(selected);
    if (selected.isEmpty()) {
      target.setOutcome(ConversationOutcome.EMPTY_EXPLANATION_SELECTION);
    }
  }

  /** Maps literal selectors to displayed books while retaining unresolved selections. */
  private static List<String> resolveSelectors(List<String> selectors, List<Recommendation> page) {
    return selectors.stream()
        .flatMap(
            value ->
                value.equals("*")
                    ? page.stream().map(book -> book.getRank().toString())
                    : java.util.stream.Stream.of(value))
        .map(
            value -> {
              var matches = select(page, value);
              return matches.size() == 1 ? matches.getFirst().getRank().toString() : value;
            })
        .toList();
  }

  /** Returns the first selector that cannot be matched to the supplied page. */
  static String firstUnresolved(ExplanationSelection selection, List<Recommendation> page) {
    return java.util.stream.Stream.concat(
            selection.include().stream(), selection.exclude().stream())
        .filter(value -> select(page, value).size() != 1)
        .findFirst()
        .orElse(null);
  }

  /** Finds displayed recommendations matching the supplied rank, position, or title selector. */
  private static List<Recommendation> select(List<Recommendation> page, String value) {
    Integer position = ConversationText.relativeSelectionReply(value, page.size());
    if (position != null) {
      return List.of(page.get(position - 1));
    }
    Integer rank = ConversationText.selectionReply(value);
    if (rank != null) {
      var ranked = page.stream().filter(book -> rank.equals(book.getRank())).toList();
      if (!ranked.isEmpty()) {
        return ranked;
      }
    }
    String title = AudiobookTitle.normalize(value);
    return title.isBlank() ? List.of() : matchingTitles(page, title);
  }

  /** Reports whether the reply identifies a title on the retained displayed page. */
  static boolean matchesDisplayedTitle(Session session, String title) {
    var previous = latestDisplayedSearch(session);
    String normalized = AudiobookTitle.normalize(title);
    return previous != null
        && previous.getRecommendations() != null
        && !normalized.isEmpty()
        && !matchingTitles(previous.getRecommendations(), normalized).isEmpty();
  }

  /** Finds displayed books whose titles match the supplied selector. */
  private static List<Recommendation> matchingTitles(List<Recommendation> page, String title) {
    var exact =
        page.stream()
            .filter(book -> AudiobookTitle.normalize(book.getTitle()).equals(title))
            .toList();
    return exact.isEmpty()
        ? page.stream()
            .filter(book -> AudiobookTitle.normalize(book.getTitle()).contains(title))
            .toList()
        : exact;
  }

  /** Returns the telemetry label describing how this action obtained its results. */
  public String retrievalMode(ConversationDraft result) {
    return "SESSION_EXPLANATION";
  }
}
