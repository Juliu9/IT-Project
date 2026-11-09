package com.gen3.recommenderagent.application.conversation.intent;

import static com.gen3.recommenderagent.application.conversation.ConversationHistory.latestDisplayedSearch;
import static com.gen3.recommenderagent.application.conversation.search.SearchCriteriaMerger.merge;
import static com.gen3.recommenderagent.inputparser.CommandValues.intent;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.ConversationHistory;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.conversation.ExplanationSelection;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.ConversationText;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.response.ResponseBudget;
import com.gen3.shared.audiobookcatalogue.model.AudiobookTitle;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(13)
public final class ExplainRecommendationHandler implements ConversationHandler {
  public static final ConversationAction ACTION = ConversationAction.of("EXPLAIN_RECOMMENDATION");
  public static final ConversationOutcome NOTHING_TO_EXPLAIN =
      ConversationOutcome.of("NOTHING_TO_EXPLAIN");
  public static final ConversationOutcome EMPTY_EXPLANATION_PAGE =
      ConversationOutcome.of("EMPTY_EXPLANATION_PAGE");
  public static final ConversationOutcome EMPTY_EXPLANATION_SELECTION =
      ConversationOutcome.of("EMPTY_EXPLANATION_SELECTION");
  public static final ConversationQuestion EXPLANATION_TARGET_CLARIFICATION =
      ConversationQuestion.of(
          "EXPLANATION_TARGET_CLARIFICATION",
          ConversationQuestion.Context.EXPLANATION,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Explanation::new);

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      EXPLAIN_RECOMMENDATION=why one or more retained books were recommended.
      Explanations and retained recommendations:
      Retained recommendations are the current page regardless of the latest action. Their ranks are book positions, not list offsets. Help or explaining one book does not replace that page.
      Explanation ordinals select ONE book: "the second one’s reason" -> referenceRank=2; "why the sixth book" -> referenceRank=6. Use EXPLAIN_RECOMMENDATION and omit bookCount.
      "Explain two more" means explain rank 2 in more detail, not explain two books. Whole-page explanations omit referenceRank and referenceTitle; never default to rank 1.
      Preserve only explicitly stated ranks, including invalid ones, and titles. Bare ordinals such as "second one" are UNKNOWN with their stated reference; Java resolves contextual selections. Do not infer "that one" from the latest action alone.
      Use explanationSelection only for multiple or relative explanation targets: {include:["2","4","last","The Hobbit"],exclude:["first","Harry Potter"]}. An omitted/empty include means all. Copy selectors as stated; former=first and latter=last. Do not guess ranks for relative selectors.
      "Explain all except The Hobbit and Dune" -> explanationSelection={exclude:["The Hobbit","Dune"]}; omit referenceRank/referenceTitle.
      "Explain The Hobbit and Dune" -> explanationSelection={include:["The Hobbit","Dune"]}. Preserve every explicitly named title; do not treat "The" as disposable.
      """;
  }

  @Override
  public Set<String> commands() {
    return Set.of(
        "explain",
        "explain the recommendations",
        "explain these recommendations",
        "why these books",
        "why these recommendations",
        "why those recommendations",
        "why did i get these recommendations",
        "why did i get these books",
        "why did you recommend these books",
        "why did you recommend these audiobooks",
        "why did you recommend these",
        "why were these recommended",
        "why were these audiobooks recommended",
        "explain all the recommendations",
        "explain these books",
        "why were these books recommended");
  }

  @Override
  public boolean retainsExplanationSelection() {
    return true;
  }

  @Override
  public UserIntent recognize(String command) {
    var exact = commands().contains(command);
    if (exact) {
      return intent(intents().iterator().next());
    }
    var explanationResult = explanation(command);
    if (explanationResult != null) {
      return explanationResult;
    }
    return null;
  }

  /** Recognizes explanation requests and extracts any stated recommendation selectors. */
  private UserIntent explanation(String command) {
    String target;
    if (command.startsWith("explain ")) {
      target = command.substring(8);
    } else if (command.startsWith("why ")) {
      target = command.substring(4);
    } else {
      return null;
    }
    if (target.matches(
        "(?:(?:the |book |number ))?[-−]?(?:[0-9]+\\.[0-9]+|\\.[0-9]+)(?: (?:book|one))?(?: more| in more detail)?")) {
      var invalid = intent(ACTION);
      invalid.setReferenceRank(BigInteger.ZERO);
      return invalid;
    }
    String[] sides = target.split(" (?:except|except for|but|but not) ", 2);
    var included = selectors(sides[0]);
    var excluded =
        sides.length == 2
            ? ConversationText.isWholePageReply(sides[1])
                ? java.util.List.of("*")
                : selectors(sides[1])
            : java.util.List.<String>of();
    if (included == null || excluded == null) {
      return null;
    }
    var parsed = intent(ACTION);
    if (sides.length == 1 && included.size() == 1) {
      Integer rank = ConversationText.selectionReply(included.getFirst());
      if (rank != null) {
        parsed.setReferenceRank(BigInteger.valueOf(rank));
      } else {
        parsed.setExplanationSelection(new ExplanationSelection(included, excluded));
      }
    } else {
      parsed.setExplanationSelection(new ExplanationSelection(included, excluded));
    }
    return parsed;
  }

  /** Extracts rank or title selectors used to identify explanation targets. */
  private java.util.List<String> selectors(String text) {
    text = text.replaceFirst(" (?:more|in more detail)$", "");
    if (ConversationText.isWholePageReply(text)
        || java.util.Set.of("these", "these books", "these recommendations", "the recommendations")
            .contains(text)) {
      return java.util.List.of();
    }
    var values = text.replaceFirst("^books? ", "").split("\\s*(?:,| and )\\s*");
    for (String value : values) {
      if (ConversationText.selectionReply(value) == null
          && ConversationText.relativeSelectionReply(value, 2) == null) {
        return null;
      }
    }
    return java.util.List.of(values);
  }

  @Override
  public boolean explainsBooks() {
    return true;
  }

  /** Resolves the requested explanation against the retained displayed recommendation page. */
  public ConversationDraft resolve(Session session, ConversationDraft input) {
    if (session == null) {
      input.setOutcome(NOTHING_TO_EXPLAIN);
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
      target.setOutcome(NOTHING_TO_EXPLAIN);
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
      target.setOutcome(EMPTY_EXPLANATION_PAGE);
      return;
    }
    if (target.getExplanationSelection() != null) {
      resolveSubset(target, session, page);
      return;
    }
    session.setPendingExplanation(
        ExplainRecommendationHandler.EXPLANATION_TARGET_CLARIFICATION, null);
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
      target.setPendingQuestion(EXPLANATION_TARGET_CLARIFICATION);
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
      session.setPendingExplanation(
          ExplainRecommendationHandler.EXPLANATION_TARGET_CLARIFICATION, normalized);
      target.setPendingQuestion(EXPLANATION_TARGET_CLARIFICATION);
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
    session.setPendingExplanation(
        ExplainRecommendationHandler.EXPLANATION_TARGET_CLARIFICATION, null);
    target.setRecommendations(selected);
    if (selected.isEmpty()) {
      target.setOutcome(EMPTY_EXPLANATION_SELECTION);
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
  public static String firstUnresolved(ExplanationSelection selection, List<Recommendation> page) {
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
  public static boolean matchesDisplayedTitle(Session session, String title) {
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

  @Override
  public String outcomeResponse(ConversationTurn turn) {
    if (turn.getOutcome() == null) {
      return null;
    }
    if (turn.getOutcome() == EMPTY_EXPLANATION_SELECTION) {
      return "There aren't any books left to explain. Which books would you like me to explain?";
    }
    if (turn.getOutcome() == NOTHING_TO_EXPLAIN) {
      return "I haven't recommended any books in this session yet. What kind of audiobook would you like?";
    }
    if (turn.getOutcome() == EMPTY_EXPLANATION_PAGE) {
      return "I didn't find any books in the latest search to explain. Would you like to try a different search?";
    }
    return null;
  }

  /** Resolves whole-page or replacement selections for a pending explanation. */
  private ConversationDraft resolveExplanationSelection(
      Session session, ConversationDraft effective, String reply) {
    // The parser has no session context; resolve short answers against the outstanding question.
    if (session.getPendingQuestionKind() == EXPLANATION_TARGET_CLARIFICATION
        && ConversationText.normalizeCommand(reply).matches("[-−]?(?:[0-9]+\\.[0-9]+|\\.[0-9]+)")) {
      effective.setIntent(ACTION);
      com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput(
          effective, this);
      effective.setInvalidBookCount(false);
      effective.setReferenceRank(0);
      effective.setReferenceTitle(null);
      effective.setExplanationSelection(null);
      return effective;
    }
    if (session.getPendingQuestionKind() == EXPLANATION_TARGET_CLARIFICATION
        && ConversationText.isWholePageReply(reply)) {
      effective.setIntent(ACTION);
      com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput(
          effective, this);
      effective.setReferenceRank(null);
      effective.setReferenceTitle(null);
      effective.setExplanationSelection(null);
      session.setPendingExplanation(
          ExplainRecommendationHandler.EXPLANATION_TARGET_CLARIFICATION, null);
      return effective;
    }
    if (session.getPendingQuestionKind() == EXPLANATION_TARGET_CLARIFICATION
        && session.getPendingExplanation() != null
        && !effective.startsNewSearch()
        && (effective.getIntent() == UnknownHandler.ACTION
            || effective.getIntent() == RecommendationHandler.ACTION
            || effective.getIntent() == RefineHandler.ACTION)) {
      var search = ConversationHistory.latestDisplayedSearch(session);
      var page = search == null ? List.<Recommendation>of() : search.getRecommendations();
      String unresolved = firstUnresolved(session.getPendingExplanation(), page);
      if (unresolved != null
          && (ConversationText.selectionReply(reply) != null
              || matchesDisplayedTitle(session, reply))) {
        effective.setIntent(ACTION);
        com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput(
            effective, this);
        effective.setReferenceRank(null);
        effective.setReferenceTitle(null);
        effective.setExplanationSelection(
            session.getPendingExplanation().replace(unresolved, reply));
        return effective;
      }
    }
    return null;
  }

  /** Maps numeric or relative selection replies to ranks on the displayed page. */
  private Integer resolveSelection(Session session, String reply) {
    Integer selection = ConversationText.selectionReply(reply);
    if (session.getPendingQuestionKind() == EXPLANATION_TARGET_CLARIFICATION) {
      var search = ConversationHistory.latestDisplayedSearch(session);
      var page =
          search == null || search.getRecommendations() == null
              ? List.<Recommendation>of()
              : search.getRecommendations();
      Integer relative = ConversationText.relativeSelectionReply(reply, page.size());
      if (relative != null) {
        selection =
            page.stream()
                .sorted(java.util.Comparator.comparing(Recommendation::getRank))
                .toList()
                .get(relative - 1)
                .getRank();
      } else if (selection != null
          && !page.stream().map(Recommendation::getRank).toList().contains(selection)
          && matchesDisplayedTitle(session, reply)) {
        selection = null;
      }
    }
    return selection;
  }

  /** Converts a pending rank or displayed-title answer into an explanation request. */
  private ConversationDraft resolveExplanationTarget(
      Session session, ConversationDraft effective, String reply, Integer selection) {
    if (session.getPendingQuestionKind() == EXPLANATION_TARGET_CLARIFICATION
        && (selection != null
            || (!effective.startsNewSearch()
                && !reply.isBlank()
                && ((effective.getIntent() == UnknownHandler.ACTION
                        || effective.getIntent() == RecommendationHandler.ACTION
                        || effective.getIntent() == RefineHandler.ACTION)
                    && matchesDisplayedTitle(session, reply))))) {
      effective.setIntent(ACTION);
      com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput(
          effective, this);
      effective.setReferenceRank(selection);
      effective.setReferenceTitle(selection == null ? reply : null);
      return effective;
    }
    return null;
  }

  @Override
  public void preparePendingReply(Session session, ConversationDraft input, String reply) {
    if (session.getPendingQuestionKind() == EXPLANATION_TARGET_CLARIFICATION
        && input.isInvalidBookCount()
        && reply.matches("[-−]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")) {
      input.setInvalidBookCount(false);
      input.setIntent(UnknownHandler.ACTION);
      input.setBookCount(null);
    }
  }

  @Override
  public boolean isContextualReply(Session session, ConversationDraft request, String reply) {
    return session.getPendingQuestionKind() == EXPLANATION_TARGET_CLARIFICATION
        && (ConversationText.selectionReply(reply) != null
            || ConversationText.relativeSelectionReply(reply, 5) != null);
  }

  @Override
  public ConversationDraft resolvePendingReply(
      Session session, ConversationDraft input, String reply) {
    var result = resolveExplanationSelection(session, input, reply);
    return result != null
        ? result
        : resolveExplanationTarget(session, input, reply, resolveSelection(session, reply));
  }

  @Override
  public void attachQuestionContext(Session session, ConversationDraft request) {
    if (session.getPendingQuestionKind() == EXPLANATION_TARGET_CLARIFICATION) {
      request.setExplanationSelection(session.getPendingExplanation());
      var search = ConversationHistory.latestDisplayedSearch(session);
      if (search != null) {
        request.setRecommendations(search.getRecommendations());
      }
    }
  }

  @Override
  public Set<ConversationQuestion> questions() {
    return Set.of(EXPLANATION_TARGET_CLARIFICATION);
  }

  @Override
  public String questionResponse(
      ConversationQuestion question, ConversationTurn currentRequest, int labelWords) {
    if (question == EXPLANATION_TARGET_CLARIFICATION) {
      return explanationClarification(currentRequest, labelWords);
    }
    return null;
  }

  /** Builds clarification wording for unresolved recommendation selections. */
  private String explanationClarification(ConversationTurn request, int labelWords) {
    List<Recommendation> page =
        request.getRecommendations() == null ? List.of() : request.getRecommendations();
    String titles =
        page.stream()
            .map(book -> book.getRank() + ". " + ResponseBudget.label(book.getTitle(), labelWords))
            .collect(Collectors.joining(". "));
    var selection = request.getExplanationSelection();
    String unresolved =
        selection == null
            ? null
            : java.util.stream.Stream.concat(
                    selection.include().stream(), selection.exclude().stream())
                .filter(
                    value ->
                        page.stream().noneMatch(book -> book.getRank().toString().equals(value)))
                .findFirst()
                .orElse(null);
    return (unresolved == null
            ? ""
            : "I couldn't uniquely identify " + ResponseBudget.label(unresolved, labelWords) + ". ")
        + "I shared "
        + page.size()
        + " book"
        + (page.size() == 1 ? "" : "s")
        + " with you: "
        + titles
        + ". Which one would you like me to explain?";
  }

  @Override
  public boolean questionActive(ConversationQuestion question, ConversationTurn turn) {
    var action = turn.getResponseAction() == null ? turn.getIntent() : turn.getResponseAction();
    return action == ACTION || turn.getIntent() == RepeatHandler.ACTION;
  }

  @Override
  public void finishQuestion(ConversationDraft request) {
    var outcome = request.getOutcome();
    if (outcome == EMPTY_EXPLANATION_SELECTION) {
      if (request.getPendingQuestion() != null) {
        request.setOutcome(null);
      }
      request.setPendingQuestion(EXPLANATION_TARGET_CLARIFICATION);
    } else if (outcome == EMPTY_EXPLANATION_PAGE) {
      if (request.getPendingQuestion() == null) {
        request.setPendingQuestion(RecommendationHandler.SEARCH_CHANGE_OFFER);
      } else {
        request.setOutcome(null);
      }
    } else {
      ConversationHandler.super.finishQuestion(request);
    }
  }

  @Override
  public Set<ConversationOutcome> outcomes() {
    return Set.of(NOTHING_TO_EXPLAIN, EMPTY_EXPLANATION_PAGE, EMPTY_EXPLANATION_SELECTION);
  }
}
