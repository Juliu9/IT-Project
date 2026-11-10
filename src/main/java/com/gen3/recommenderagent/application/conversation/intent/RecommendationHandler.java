package com.gen3.recommenderagent.application.conversation.intent;

import static com.gen3.recommenderagent.inputparser.CommandValues.intent;

import com.gen3.recommenderagent.application.conversation.ConversationHistory;
import com.gen3.recommenderagent.application.conversation.ConversationRules;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.application.conversation.search.SearchContextResolver;
import com.gen3.recommenderagent.application.conversation.search.SearchCriteriaMerger;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SimilarBookReference;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.ConversationText;
import com.gen3.recommenderagent.inputparser.CountInput;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.response.ResponseBudget;
import com.gen3.shared.audiobookcatalogue.model.AudiobookTitle;
import com.gen3.shared.audiobookcatalogue.model.PersonNameNormalizer;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(11)
public final class RecommendationHandler extends SearchContextResolver {
  public static final ConversationAction ACTION = ConversationAction.of("RECOMMENDATION");
  public static final ConversationQuestion PREFERENCE_CLARIFICATION =
      ConversationQuestion.of(
          "PREFERENCE_CLARIFICATION",
          ConversationQuestion.Context.PREFERENCE,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Preference::new);
  public static final ConversationQuestion SEARCH_CHANGE_OFFER =
      ConversationQuestion.of(
          "SEARCH_CHANGE_OFFER",
          ConversationQuestion.Context.SEARCH,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Search::new);
  public static final ConversationQuestion SEARCH_CLARIFICATION =
      ConversationQuestion.of(
          "SEARCH_CLARIFICATION",
          ConversationQuestion.Context.SEARCH,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Search::new);
  public static final ConversationQuestion SIMILAR_BOOK_NOT_FOUND =
      ConversationQuestion.of(
          "SIMILAR_BOOK_NOT_FOUND",
          ConversationQuestion.Context.SIMILAR_BOOK,
          com.gen3.recommenderagent.domain.session.PendingQuestion.SimilarBook::new);
  public static final ConversationQuestion SIMILAR_BOOK_REQUIRED =
      ConversationQuestion.of(
          "SIMILAR_BOOK_REQUIRED",
          ConversationQuestion.Context.SIMILAR_BOOK,
          com.gen3.recommenderagent.domain.session.PendingQuestion.SimilarBook::new);
  public static final ConversationQuestion SIMILAR_BOOK_SELECTION =
      ConversationQuestion.of(
          "SIMILAR_BOOK_SELECTION",
          ConversationQuestion.Context.SIMILAR_BOOK,
          com.gen3.recommenderagent.domain.session.PendingQuestion.SimilarBook::new);
  public static final ConversationQuestion SIMILAR_BOOK_UNAVAILABLE =
      ConversationQuestion.of(
          "SIMILAR_BOOK_UNAVAILABLE",
          ConversationQuestion.Context.SIMILAR_BOOK,
          com.gen3.recommenderagent.domain.session.PendingQuestion.SimilarBook::new);

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      RECOMMENDATION=new or self-contained criteria, including a bare taste with no verb.
      Search wording and personalisation:
      Extract the stated intent and criteria, but do not decide whether the input starts a new search, answers a pending question, or takes precedence over session state. Java owns that conversational policy.
      A bare taste or constraint with no search instruction is still RECOMMENDATION when self-contained: "horror" -> RECOMMENDATION, positiveSemanticQuery.genres=["horror"]. It is never UPDATE_PREFERENCES without explicit saved-preference wording.
      A new recommendation with any positive or negative semantic criteria uses personalised=false. Otherwise, set personalised=true only for explicit use of preferences/favourites/history, or for a generic recommendation request with no criteria. "I want" alone, a reference book, or author/narrator style similarity does not imply personalisation.
      "Give me some books" -> RECOMMENDATION, personalised=true, with no invented criteria.
      Personalisation never removes stated criteria: "books I would like by Suzanne Collins" -> personalised=true, mustInclude.authors=["Suzanne Collins"].
      "Find an audiobook based on my reading preferences by Agatha Christie under six hours" -> personalised=true, mustInclude.authors=["Agatha Christie"], mustInclude.duration="under 6 hours", with no invented semantic tastes.

      Similar-book references:
      For "similar to TITLE by AUTHOR", use RECOMMENDATION with similarToTitle and similarToAuthor. The author identifies the reference; it is not a recommended-book author filter.
      A single named title goes to catalogue lookup. Do not infer themes, place the title in semantic criteria, or infer that a title refers to a series. Plural recommendation words such as books or audiobooks do not imply multiple references or series scope.
      Set similarToClarificationRequired=true only when the user explicitly requests a series/trilogy/saga as a whole or names multiple reference books. For multiple references, omit similarToTitle because it is one string; Java asks the user to choose. A single title containing "and", such as War and Peace, remains one title.
      "books similar to The Hunger Games" -> {"intent":"RECOMMENDATION","similarToTitle":"The Hunger Games","similarToClarificationRequired":false}.
      "Books similar to The Hunger Games by Suzanne Collins under ten hours" -> {"intent":"RECOMMENDATION","similarToTitle":"The Hunger Games","similarToAuthor":"Suzanne Collins","mustInclude":{"duration":"under 10 hours"}}. Omit positiveSemanticQuery and mustInclude.authors; the reference title and author are not inferred tastes or result filters.
      "books similar to the Hunger Games series" -> {"intent":"RECOMMENDATION","similarToClarificationRequired":true}.
      "books similar to The Hunger Games and Divergent" -> {"intent":"RECOMMENDATION","similarToClarificationRequired":true}.
      Preserve additional criteria and apply the personalisation rules above.
      """;
  }

  @Override
  public boolean acceptsSearchSemantics() {
    return true;
  }

  @Override
  public boolean abandonsPendingQuestion(SessionRequest request) {
    return request.startsNewSearch();
  }

  @Override
  public boolean isSearchAction() {
    return true;
  }

  private static final Pattern APPROXIMATE_YEAR_REQUEST =
      Pattern.compile(
          "(?:(?:recommend|show me|give me|i want|i'd like|i would like) )?"
              + "(?:some )?(?:books|audiobooks) from ((?:around|(?:the )?period) [0-9]{4})");

  @Override
  public UserIntent recognize(String command) {
    var exact = commands().contains(command);
    if (exact) {
      return intent(intents().iterator().next());
    }
    var negativeSearchResult = negativeSearch(command);
    if (negativeSearchResult != null) {
      return negativeSearchResult;
    }
    var publicationYearCommandResult = publicationYearCommand(command);
    if (publicationYearCommandResult != null) {
      return publicationYearCommandResult;
    }
    var personalisedRecommendationResult = personalisedRecommendation(command);
    if (personalisedRecommendationResult != null) {
      return personalisedRecommendationResult;
    }
    return null;
  }

  /** Recognizes a search that states semantic content to avoid. */
  private UserIntent negativeSearch(String command) {
    var negative =
        Pattern.compile("^(?:the same but )?less (violent|dark|scary|romantic)$").matcher(command);
    var without =
        Pattern.compile(
                "^(?:only )?(?:books|audiobooks) without (" + ConversationText.GENRE_PATTERN + ")$")
            .matcher(command);
    if (negative.matches() || without.matches()) {
      var parsed = intent(negative.matches() ? RefineHandler.ACTION : ACTION);
      var query = new com.gen3.recommenderagent.domain.search.SemanticQuery();
      if (negative.matches()) {
        query.setTopics(java.util.List.of(negative.group(1)));
      } else {
        query.setGenres(java.util.List.of(without.group(1)));
      }
      parsed.setNegativeSemanticQuery(query);
      return parsed;
    }
    return null;
  }

  /** Recognizes supported exact publication-year search wording. */
  private UserIntent publicationYearCommand(String command) {
    var approximateYear = APPROXIMATE_YEAR_REQUEST.matcher(command);
    if (approximateYear.matches()) {
      var parsed = intent(ACTION);
      var criteria = new ExactCriteria();
      criteria.setPublicationYear(approximateYear.group(1));
      parsed.setMustInclude(criteria);
      return parsed;
    }
    if (ConversationText.isOlderRefinement(command)) {
      var parsed = intent(RefineHandler.ACTION);
      var criteria = new ExactCriteria();
      criteria.setPublicationYear("older");
      parsed.setMustInclude(criteria);
      return parsed;
    }
    return null;
  }

  /** Recognizes a complete request for recommendations based on stored user taste. */
  private UserIntent personalisedRecommendation(String command) {
    if (java.util.Set.of(
            "give me some books",
            "give me some audiobooks",
            "recommend some books",
            "recommend some audiobooks")
        .contains(command)) {
      var parsed = intent(ACTION);
      parsed.setPersonalised(true);
      return parsed;
    }
    return null;
  }

  /** Restores an unfinished search when the user accepts or answers its clarification. */
  private ConversationDraft resolveSearchChangeReply(
      Session session, ConversationDraft effective, String reply) {
    if (ConversationText.isAffirmativeReply(reply)
        && (session.getPendingQuestionKind() == SEARCH_CHANGE_OFFER)) {
      var previous = ConversationHistory.latestDisplayedSearch(session);
      if (previous != null) {
        session.setPendingRefinementRequest(ConversationDraft.toStoredTurn(previous));
      }
      effective.setIntent(ACTION);
      com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput(
          effective, this);
      effective.setPendingQuestion(RefineHandler.SEARCH_CHANGE_CLARIFICATION);
      effective.setSearchClarificationRequired(true);
      return effective;
    }
    resumeSearchReply(session, effective, SEARCH_CLARIFICATION, ACTION);
    return null;
  }

  /** {@inheritDoc} */
  private ConversationDraft resolveSimilarBookReply(
      Session session, ConversationDraft input, String reply) {
    if (!(session.getPendingQuestion() instanceof PendingQuestion.SimilarBook pending)) {
      return input;
    }
    var choices = pending.getRequest().getSimilarBookChoices();
    if (isReferenceTitleAnswer(input, reply)) {
      var named = anotherReference(pending.getRequest(), input);
      named.setSimilarToTitle(input.getSimilarToTitle());
      named.setSimilarToAuthor(input.getSimilarToAuthor());
      named.setSimilarToClarificationRequired(false);
      return named;
    }
    String replacement = ConversationText.replacementReferenceTitle(reply);
    if (replacement != null) {
      var named = anotherReference(pending.getRequest(), input);
      named.setSimilarToTitle(replacement);
      named.setSimilarToClarificationRequired(false);
      return named;
    }
    if ((!choices.isEmpty() && ConversationText.isChoiceRejection(reply))
        || (pending.getKind() == SIMILAR_BOOK_UNAVAILABLE
            && ConversationText.isAffirmativeReply(reply))) {
      return anotherReference(pending.getRequest(), input);
    }
    Integer selection = ConversationText.selectionReply(reply);
    if (selection == null) {
      selection = ConversationText.relativeSelectionReply(reply, choices.size());
    }
    if (selection == null
        && (input.startsNewSearch()
            || input.getSimilarToTitle() != null
            || input.isSimilarToClarificationRequired())) {
      return input;
    }
    if (selection == null
        && input.getIntent() != UnknownHandler.ACTION
        && input.getIntent() != ACTION
        && input.getIntent() != RefineHandler.ACTION) {
      return input;
    }
    AudiobookTitle selected = select(choices, reply, selection);
    if (selected == null) {
      return unresolved(session, pending.getRequest(), pending.getKind(), input, selection);
    }
    var resumed = ConversationHistory.restoreSearchCriteria(pending.getRequest(), input);
    resumed.setIntent(ACTION);
    resumed.setStartsNewSearch(true);
    resumed.setSearchClarificationRequired(false);
    resumed.setOutcome(null);
    resumed.setSimilarBookChoices(List.of());
    resumed
        .getSearchContext()
        .setSimilarBook(
            new SimilarBookReference(
                selected.bookId(), selected.title(), selected.authors(), null));
    return resumed;
  }

  /** Restores the pending search with the selected reference book. */
  private AudiobookTitle select(List<AudiobookTitle> choices, String reply, Integer selection) {
    if (selection != null && selection > 0 && selection <= choices.size()) {
      return choices.get(selection - 1);
    }
    if (reply.isBlank()) {
      return null;
    }
    var matches =
        choices.stream()
            .filter(
                book ->
                    AudiobookTitle.normalize(book.title()).equals(AudiobookTitle.normalize(reply))
                        || book.authors().stream()
                            .anyMatch(
                                author ->
                                    PersonNameNormalizer.comparisonKey(author)
                                        .equals(PersonNameNormalizer.comparisonKey(reply))))
            .toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  /** Keeps the reference-book question active when the reply does not identify a choice. */
  private ConversationDraft unresolved(
      Session session,
      ConversationTurn pending,
      ConversationQuestion decision,
      ConversationDraft input,
      Integer selection) {
    var choices = pending.getSimilarBookChoices();
    if (!choices.isEmpty() && selection != null) {
      var again = ConversationHistory.restoreSearchCriteria(pending);
      again.setPendingQuestion(SIMILAR_BOOK_SELECTION);
      again.setSearchClarificationRequired(true);
      again.setSimilarBookChoices(choices);
      again.setRequestId(input.getRequestId());
      again.setRawText(input.getRawText());
      return again;
    }
    if (choices.isEmpty()
        && decision == SIMILAR_BOOK_NOT_FOUND
        && (input.getIntent() == ACTION || input.getIntent() == RefineHandler.ACTION)) {
      var tastes = SearchCriteriaMerger.copyOf(input);
      SearchCriteriaMerger.merge(ConversationHistory.restoreSearchCriteria(pending), tastes);
      tastes.setIntent(ACTION);
      tastes.setStartsNewSearch(true);
      session.setPendingQuestion(null);
      return tastes;
    }
    return input;
  }

  /** Interprets wording that supplies a different reference title. */
  private ConversationDraft anotherReference(ConversationTurn pending, ConversationDraft input) {
    var another = ConversationHistory.restoreSearchCriteria(pending, input);
    another.setIntent(ACTION);
    another.setStartsNewSearch(true);
    another.setSimilarToTitle(null);
    another.setSimilarToAuthor(null);
    another.setSimilarToClarificationRequired(true);
    another.getSearchContext().setSimilarBook(null);
    another.getSearchContext().setSimilarityQueryVector(null);
    another.setSimilarBookChoices(List.of());
    another.setSearchClarificationRequired(false);
    another.setOutcome(null);
    return another;
  }

  @Override
  public void preparePendingReply(Session session, ConversationDraft input, String reply) {
    if (session.getPendingQuestion() instanceof PendingQuestion.SimilarBook) {
      com.gen3.recommenderagent.application.conversation.ConversationRules
          .reinterpretSelectionCount(input, reply);
    }
  }

  /** Recognizes a bare parsed title even when request normalization marks it as a new search. */
  private boolean isReferenceTitleAnswer(ConversationDraft input, String reply) {
    String title = input.getSimilarToTitle();
    return title != null && (!input.startsNewSearch() || title.equalsIgnoreCase(reply));
  }

  @Override
  public boolean isContextualReply(Session session, ConversationDraft input, String reply) {
    return session.getPendingQuestion() instanceof PendingQuestion.SimilarBook
        && (isReferenceTitleAnswer(input, reply)
            || ConversationText.selectionReply(reply) != null
            || ConversationText.relativeSelectionReply(reply, 5) != null);
  }

  @Override
  public ConversationDraft resolvePendingReply(
      Session session, ConversationDraft input, String reply) {
    var result = resolveSearchChangeReply(session, input, reply);
    if (result != null) {
      return result;
    }
    result = resolveSimilarBookReply(session, input, reply);
    return result == input ? null : result;
  }

  @Override
  public void attachQuestionContext(Session session, ConversationDraft request) {
    if (session.getPendingQuestion() instanceof PendingQuestion.SimilarBook pending) {
      request.setSimilarBookChoices(pending.getRequest().getSimilarBookChoices());
    } else if (session.getPendingQuestionKind() == SEARCH_CLARIFICATION) {
      attachPendingSearchCriteria(session, request);
    }
  }

  @Override
  public boolean keepsUnansweredQuestion(ConversationQuestion question) {
    return question == PREFERENCE_CLARIFICATION;
  }

  @Override
  public Set<ConversationQuestion> questions() {
    return Set.of(
        PREFERENCE_CLARIFICATION,
        SEARCH_CHANGE_OFFER,
        SEARCH_CLARIFICATION,
        SIMILAR_BOOK_NOT_FOUND,
        SIMILAR_BOOK_REQUIRED,
        SIMILAR_BOOK_SELECTION,
        SIMILAR_BOOK_UNAVAILABLE);
  }

  @Override
  public String questionResponse(
      ConversationQuestion question, ConversationTurn currentRequest, int labelWords) {
    if (question == SIMILAR_BOOK_SELECTION) {
      return "I found a few possible matches. Which book did you mean? "
          + java.util.stream.IntStream.range(0, currentRequest.getSimilarBookChoices().size())
              .mapToObj(
                  index -> {
                    var book = currentRequest.getSimilarBookChoices().get(index);
                    return (index + 1)
                        + ". "
                        + ResponseBudget.label(
                            book.title()
                                + (book.authors().isEmpty()
                                    ? ""
                                    : " by " + String.join(", ", book.authors())),
                            labelWords);
                  })
              .collect(Collectors.joining(". "))
          + ". You can choose a number, or share a more specific title and author.";
    }
    if (question == SIMILAR_BOOK_NOT_FOUND) {
      return "I couldn't find that book in the catalogue. What did you enjoy about it?";
    }
    if (question == SIMILAR_BOOK_REQUIRED) {
      return "Which book would you like similar recommendations for? Please give me one title.";
    }
    if (question == SIMILAR_BOOK_UNAVAILABLE) {
      return "I can't use that book to find similar audiobooks right now. Please give me another title.";
    }
    if (question == SEARCH_CLARIFICATION) {
      return "What kind of audiobook would you like?";
    }
    if (question == SEARCH_CHANGE_OFFER) {
      return "Would you like to try a different author, genre, or filter?";
    }
    if (question == PREFERENCE_CLARIFICATION) {
      return "What genres do you like?";
    }
    return null;
  }

  @Override
  public boolean questionActive(ConversationQuestion question, ConversationTurn turn) {
    return question == PREFERENCE_CLARIFICATION
        ? turn.isPreferenceClarificationRequired()
        : turn.isSearchClarificationRequired();
  }

  @Override
  public void retainQuestion(Session session, ConversationDraft request) {
    if (session.getPendingQuestion() instanceof PendingQuestion.Preference pending) {
      pending.setRequest(ConversationDraft.toStoredTurn(request));
    }
  }

  @Override
  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (effective.getIntent() == ACTION) {
      if (ConversationRules.hasQuery(effective.getPositiveSemanticQuery())
          || ConversationRules.hasQuery(effective.getNegativeSemanticQuery())) {
        effective.setPersonalised(false);
      } else if (!ConversationRules.hasSearchCriteria(effective)) {
        effective.setPersonalised(true);
      }
    }
    return super.resolve(session, effective);
  }

  @Override
  public boolean invalidatesBatch(SessionRequest request) {
    return true;
  }

  @Override
  public boolean recognizesInvalidCount(String rawText, String modelText) {
    return !CountInput.setting(rawText) && !modelText.equals("1") && !CountInput.more(rawText);
  }

  @Override
  protected boolean clearsQuestionWithoutReference() {
    return true;
  }

  @Override
  public boolean requiresSavedPreferences(ConversationDraft request, Session session) {
    return needsSavedPreferences(request, session);
  }

  /** Applies the preference question owned by recommendation when another search requests it. */
  public static boolean needsSavedPreferences(ConversationDraft request, Session session) {
    return request.isPersonalised()
        && request.getUserPreferenceSnapshot() == null
        && !(session != null
            && session.getPreferences() != null
            && session.getPreferences().hasValues())
        && !request.isPreferenceAnswer();
  }

  @Override
  public boolean requiresSemanticPreferences(
      ConversationDraft request, com.gen3.recommenderagent.embedding.SemanticQueryVectors vectors) {
    return needsSemanticPreferences(request, vectors);
  }

  /** Requires usable semantic tastes when the user explicitly asks for personalisation. */
  public static boolean needsSemanticPreferences(
      ConversationDraft request, com.gen3.recommenderagent.embedding.SemanticQueryVectors vectors) {
    return request.isPersonalised() && (vectors == null || vectors.isEmpty());
  }

  /** Describes an empty search that explicitly used saved preferences. */
  public static String personalisedNoMatches() {
    return "I couldn't find audiobooks matching your current search and saved preferences. Would you like to try a different author, genre, or filter?";
  }
}
