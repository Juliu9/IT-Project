package com.gen3.recommenderagent.application.conversation.intent;

import static com.gen3.recommenderagent.application.conversation.ConversationHistory.restoreSearchCriteria;
import static com.gen3.recommenderagent.inputparser.CommandValues.intent;

import com.gen3.recommenderagent.application.conversation.ConversationHistory;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.application.conversation.search.SearchContextResolver;
import com.gen3.recommenderagent.application.conversation.search.SearchCriteriaMerger;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.ConversationText;
import com.gen3.recommenderagent.inputparser.DurationParser;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import com.gen3.recommenderagent.inputparser.UserIntent;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(12)
public final class RefineHandler extends SearchContextResolver {
  public static final ConversationAction ACTION = ConversationAction.of("REFINE");
  public static final ConversationOutcome RECENCY_EXHAUSTED =
      ConversationOutcome.of("RECENCY_EXHAUSTED");
  public static final ConversationQuestion DURATION_RANGE_CONFIRMATION =
      ConversationQuestion.of(
          "DURATION_RANGE_CONFIRMATION",
          ConversationQuestion.Context.SEARCH,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Search::new);
  public static final ConversationQuestion LONGER_DURATION_CLARIFICATION =
      ConversationQuestion.of(
          "LONGER_DURATION_CLARIFICATION",
          ConversationQuestion.Context.SEARCH,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Search::new);
  public static final ConversationQuestion RECENCY_RANGE_CONFIRMATION =
      ConversationQuestion.of(
          "RECENCY_RANGE_CONFIRMATION",
          ConversationQuestion.Context.SEARCH,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Search::new);
  public static final ConversationQuestion RECENCY_YEAR_CLARIFICATION =
      ConversationQuestion.of(
          "RECENCY_YEAR_CLARIFICATION",
          ConversationQuestion.Context.SEARCH,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Search::new);
  public static final ConversationQuestion SEARCH_CHANGE_CLARIFICATION =
      ConversationQuestion.of(
          "SEARCH_CHANGE_CLARIFICATION",
          ConversationQuestion.Context.SEARCH,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Search::new);
  public static final ConversationQuestion SHORTER_DURATION_CLARIFICATION =
      ConversationQuestion.of(
          "SHORTER_DURATION_CLARIFICATION",
          ConversationQuestion.Context.SEARCH,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Search::new);

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      REFINE=wording that changes a search: make it/them, adjust those, add to that search, replace, or instead. Recognise this intent even without a preceding search or supplied context; Java handles missing history. A bare genre alone remains RECOMMENDATION.
      Search refinements and replacement:
      Comparative recency is REFINE with the relative phrase in mustInclude.publicationYear: "more recent" and "books from more recent times" -> "more recent"; "older ones" -> "older". Java compares the displayed page.
      "make them shorter" -> REFINE, shorter=true; "make them longer" -> REFINE, longer=true. These compare previous runtimes, not response length; do not invent hours, genres, or other limits.
      "the same but less violent" -> REFINE, negativeSemanticQuery.topics=["violent"].
      For REFINE, decide addition versus replacement separately from intent: only explicit instead/replace/clear/remove wording adds the named field path to replaceSearchFields. Also/add/too/more without replacement wording adds criteria; omit replaceSearchFields. Never numerically weight genres.
      "Replace the genres with romance" -> REFINE, positiveSemanticQuery.genres=["romance"], replaceSearchFields=["positiveSemanticQuery.genres"], with no preference edit fields. "Replace the genres in my preferences with romance" -> UPDATE_PREFERENCES, preferenceUpdates.positiveSemanticQuery.genres=["romance"], replacePreferenceFields=["positiveSemanticQuery.genres"].
      "Make it horror instead" -> REFINE, positiveSemanticQuery.genres=["horror"], replaceSearchFields=["positiveSemanticQuery.genres"].
      "Any author is fine now" -> REFINE, replaceSearchFields=["mustInclude.authors"], with no author value. Clearing a search field changes the search, not saved preferences. Omitted fields remain unchanged.
      Valid replaceSearchFields paths are positiveSemanticQuery/negativeSemanticQuery.{genres,topics,authors,narrators} and mustInclude/mustNotInclude.{authors,narrators,language,source,duration,publicationYear}.
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

  @Override
  public UserIntent recognize(String command) {
    var exact = commands().contains(command);
    if (exact) {
      return intent(intents().iterator().next());
    }
    var longerRefinementResult = longerRefinement(command);
    if (longerRefinementResult != null) {
      return longerRefinementResult;
    }
    var recencyRefinementResult = recencyRefinement(command);
    if (recencyRefinementResult != null) {
      return recencyRefinementResult;
    }
    var searchFieldRemovalResult = searchFieldRemoval(command);
    if (searchFieldRemovalResult != null) {
      return searchFieldRemovalResult;
    }
    var shorterRefinementResult = shorterRefinement(command);
    if (shorterRefinementResult != null) {
      return shorterRefinementResult;
    }
    return null;
  }

  /** Recognizes a command requesting longer recommendations. */
  private UserIntent longerRefinement(String command) {
    if (java.util.Set.of(
            "make them longer",
            "make it longer",
            "longer ones",
            "longer books",
            "longer audiobooks")
        .contains(command)) {
      var parsed = intent(ACTION);
      parsed.setLonger(true);
      return parsed;
    }
    return null;
  }

  /** Recognizes commands that refine publication recency. */
  private UserIntent recencyRefinement(String command) {
    if (ConversationText.isRecencyRefinement(command)) {
      var parsed = intent(ACTION);
      var criteria = new ExactCriteria();
      criteria.setPublicationYear("more recent");
      parsed.setMustInclude(criteria);
      return parsed;
    }
    return null;
  }

  /** Recognizes explicit removal of a field from the active search. */
  private UserIntent searchFieldRemoval(String command) {
    String cleared =
        switch (command) {
          case "any author",
              "any author is fine",
              "any author is fine now",
              "remove the author filter" ->
              "mustInclude.authors";
          case "any narrator", "any narrator is fine", "remove the narrator filter" ->
              "mustInclude.narrators";
          case "remove the time limit", "remove the duration limit", "any duration" ->
              "mustInclude.duration";
          case "any publication year", "remove the year filter" -> "mustInclude.publicationYear";
          default -> null;
        };
    if (cleared != null) {
      var parsed = intent(ACTION);
      parsed.setReplaceSearchFields(java.util.List.of(cleared));
      return parsed;
    }
    return null;
  }

  /** Recognizes a command requesting shorter recommendations. */
  private UserIntent shorterRefinement(String command) {
    if (java.util.Set.of(
            "make them shorter",
            "make it shorter",
            "make those audiobooks shorter",
            "make the previous books shorter",
            "shorter ones",
            "something shorter",
            "shorter audiobooks",
            "shorter books")
        .contains(command)) {
      var parsed = intent(ACTION);
      parsed.setShorter(true);
      return parsed;
    }
    return null;
  }

  /** Applies a duration answer to the retained shorter-or-longer refinement request. */
  private ConversationDraft resolveDurationReply(
      Session session, ConversationDraft effective, String reply) {
    if (session.getPendingQuestionKind() == SHORTER_DURATION_CLARIFICATION
        || session.getPendingQuestionKind() == LONGER_DURATION_CLARIFICATION) {
      String duration =
          ConversationText.durationReply(
              reply, session.getPendingQuestionKind() == LONGER_DURATION_CLARIFICATION);
      if (duration == null && ConversationText.isDurationOnlyReply(reply)) {
        var clarification =
            session.getPendingRefinementRequest() == null
                ? effective
                : restoreSearchCriteria(session.getPendingRefinementRequest(), effective);
        clarification.setIntent(ACTION);
        clarification.setPendingQuestion(session.getPendingQuestionKind());
        clarification.setSearchClarificationRequired(true);
        return clarification;
      }
      boolean parsedReply =
          !effective.startsNewSearch()
              && effective.getMustInclude() != null
              && effective.getMustInclude().getDuration() != null
              && (effective.getIntent() == RecommendationHandler.ACTION
                  || effective.getIntent() == ACTION
                  || effective.getIntent() == UnknownHandler.ACTION);
      if (duration != null || parsedReply) {
        effective.setIntent(ACTION);
        effective.setShorter(false);
        effective.setLonger(false);
        if (duration != null) {
          var include = new com.gen3.recommenderagent.domain.search.ExactCriteria();
          include.setDuration(duration);
          effective.setMustInclude(include);
          effective.setMustNotInclude(null);
          effective.setPositiveSemanticQuery(null);
          effective.setNegativeSemanticQuery(null);
          effective.setPersonalised(false);
          effective.setBookCount(null);
          effective.setInvalidBookCount(false);
        }
        com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput(
            effective, this);
        if (session.getPendingRefinementRequest() != null) {
          SearchCriteriaMerger.merge(
              restoreSearchCriteria(session.getPendingRefinementRequest()), effective);
        }
        return effective;
      }
    }
    return null;
  }

  /** Resumes or cancels a proposed range change after the user's confirmation. */
  private ConversationDraft resolveRangeConfirmation(
      Session session, ConversationDraft effective, String reply) {
    if ((session.getPendingQuestionKind() == RECENCY_RANGE_CONFIRMATION
            || session.getPendingQuestionKind() == DURATION_RANGE_CONFIRMATION)
        && session.getPendingRefinementRequest() != null) {
      String answer = ConversationText.normalizeCommand(reply);
      if (java.util.Set.of("no", "no thanks", "not now", "keep the previous search")
          .contains(answer)) {
        effective.setIntent(CancelHandler.ACTION);
        com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput(
            effective, this);
        return effective;
      }
      if (ConversationText.isAffirmativeReply(reply)) {
        var resumed = restoreSearchCriteria(session.getPendingRefinementRequest(), effective);
        resumed.setSearchClarificationRequired(false);
        resumed.setOutcome(null);
        session.setPendingQuestionKind(null);
        session.setPendingRefinementRequest(null);
        return resumed;
      }
    }
    return null;
  }

  /** Applies a year answer to an unfinished recency refinement. */
  private ConversationDraft resolveYearReply(
      Session session, ConversationDraft effective, String reply) {
    String yearReply = ConversationText.normalizeCommand(reply);
    if ((session.getPendingQuestionKind() == RECENCY_YEAR_CLARIFICATION
            || session.getPendingQuestionKind() == RECENCY_RANGE_CONFIRMATION)
        && !effective.startsNewSearch()
        && (effective.getIntent() == UnknownHandler.ACTION
            || effective.getIntent() == RecommendationHandler.ACTION
            || effective.getIntent() == ACTION)
        && (yearReply.matches("[0-9]{4}")
            || (effective.getMustInclude() != null
                && effective.getMustInclude().getPublicationYear() != null))) {
      effective.setIntent(ACTION);
      com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput(
          effective, this);
      var include =
          effective.getMustInclude() == null
              ? new com.gen3.recommenderagent.domain.search.ExactCriteria()
              : effective.getMustInclude();
      if (yearReply.matches("[0-9]{4}")) {
        include.setPublicationYear("from " + yearReply);
      } else if (include.getPublicationYear().matches("[0-9]{4}")) {
        include.setPublicationYear("from " + include.getPublicationYear());
      }
      effective.setMustInclude(include);
      boolean older =
          session.getPendingRefinementRequest() != null
              && session.getPendingRefinementRequest().getMustInclude() != null
              && ConversationText.isOlderRefinement(
                  session.getPendingRefinementRequest().getMustInclude().getPublicationYear());
      if (older && yearReply.matches("[0-9]{4}")) {
        include.setPublicationYear("at most " + yearReply);
      }
      if (session.getPendingRefinementRequest() != null) {
        SearchCriteriaMerger.merge(
            restoreSearchCriteria(session.getPendingRefinementRequest()), effective);
      }
      if (older || session.getPendingQuestionKind() == RECENCY_RANGE_CONFIRMATION) {
        session.setPendingQuestionKind(null);
        session.setPendingRefinementRequest(null);
      }
      return effective;
    }
    return null;
  }

  @Override
  public boolean isContextualReply(Session session, ConversationDraft request, String reply) {
    return (session.getPendingQuestionKind() == SHORTER_DURATION_CLARIFICATION
            || session.getPendingQuestionKind() == LONGER_DURATION_CLARIFICATION)
        && ConversationText.isDurationOnlyReply(reply);
  }

  @Override
  public ConversationDraft resolvePendingReply(
      Session session, ConversationDraft input, String reply) {
    resumeSearchReply(session, input, SEARCH_CHANGE_CLARIFICATION, ACTION);
    var result = resolveDurationReply(session, input, reply);
    if (result != null) {
      return result;
    }
    result = resolveRangeConfirmation(session, input, reply);
    return result != null ? result : resolveYearReply(session, input, reply);
  }

  @Override
  public Set<ConversationQuestion> questions() {
    return Set.of(
        DURATION_RANGE_CONFIRMATION,
        LONGER_DURATION_CLARIFICATION,
        RECENCY_RANGE_CONFIRMATION,
        RECENCY_YEAR_CLARIFICATION,
        SEARCH_CHANGE_CLARIFICATION,
        SHORTER_DURATION_CLARIFICATION);
  }

  @Override
  public String questionResponse(
      ConversationQuestion question, ConversationTurn currentRequest, int labelWords) {
    if (question == LONGER_DURATION_CLARIFICATION) {
      return "How long would you like the audiobooks to be? For example, at least 3 hours.";
    }
    if (question == DURATION_RANGE_CONFIRMATION) {
      return "That duration goes beyond your previous range. Would you like to extend the range?";
    }
    if (question == SHORTER_DURATION_CLARIFICATION) {
      return "How short would you like the audiobooks to be? For example, under 3 hours.";
    }
    if (question == RECENCY_RANGE_CONFIRMATION) {
      return "Those years extend beyond your previous period. Would you like to use the new year range instead?";
    }
    if (question == RECENCY_YEAR_CLARIFICATION) {
      return olderRequest(currentRequest)
          ? "I don't have publication years for those books. What ending year would you like?"
          : "I don't have publication years for those books. What year would you like me to start from? For example, from 2000.";
    }
    if (question == SEARCH_CHANGE_CLARIFICATION) {
      return "What would you like to change about your search?";
    }
    return null;
  }

  private boolean olderRequest(ConversationTurn request) {
    return request.getMustInclude() != null
        && com.gen3.recommenderagent.inputparser.ConversationText.isOlderRefinement(
            request.getMustInclude().getPublicationYear());
  }

  @Override
  public void attachQuestionContext(Session session, ConversationDraft request) {
    if ((questions().contains(session.getPendingQuestionKind())
            || session.getPendingQuestionKind() == RecommendationHandler.SEARCH_CLARIFICATION)
        && session.getPendingRefinementRequest() != null) {
      request.setMustInclude(
          restoreSearchCriteria(session.getPendingRefinementRequest()).getMustInclude());
    }
  }

  @Override
  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (session == null) {
      return effective;
    }
    if (com.gen3.recommenderagent.application.conversation.ConversationHistory.latestSearch(session)
            == null
        && !session.isAwaitingPersonalisedPreferences()) {
      if ((effective.isShorter() || effective.isLonger())
          || ConversationText.isYearRefinement(effective.getRawText())
          || (effective.getMustInclude() != null
              && ConversationText.isYearRefinement(effective.getMustInclude().getPublicationYear()))
          || !com.gen3.recommenderagent.application.conversation.ConversationRules
              .hasSearchCriteria(effective)) {
        effective.setSearchClarificationRequired(true);
        return effective;
      }
      effective.setIntent(RecommendationHandler.ACTION);
    }
    return super.resolve(session, effective);
  }

  @Override
  public boolean inheritsSearch() {
    return true;
  }

  @Override
  public boolean invalidatesBatch(SessionRequest request) {
    return true;
  }

  @Override
  public boolean retainsInvalidCountIntent() {
    return true;
  }

  @Override
  public String fallbackResponse(ConversationTurn turn, int labelWords) {
    if (turn.isPersonalised()) {
      return RecommendationHandler.personalisedNoMatches();
    }
    return "I couldn't find books matching those criteria. Would you like to try a different author, genre, or filter?";
  }

  @Override
  public void validate(
      Session session,
      ConversationDraft result,
      SessionRequest original,
      com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy filters,
      com.gen3.recommenderagent.application.recommendation.SimilarBookResolver similarBooks,
      com.gen3.recommenderagent.application.conversation.HandlerRegistry handlers) {
    if (!result.isSearchClarificationRequired()) {
      resolveDurationAnswer(result, session);
    }
    if (!result.isSearchClarificationRequired()) {
      resolveDurationRefinement(result, session);
    }
    if (!result.isSearchClarificationRequired()) {
      resolveRecencyRefinement(result, session, filters);
    }
    super.validate(session, result, original, filters, similarBooks, handlers);
  }

  @Override
  public String outcomeResponse(ConversationTurn turn) {
    if (turn.getOutcome() != RECENCY_EXHAUSTED) {
      return null;
    }
    boolean older =
        turn.getMustInclude() != null
            && ConversationText.isOlderRefinement(turn.getMustInclude().getPublicationYear());
    return older
        ? "There are no older books within the available publication years."
        : "There are no newer books within your selected period.";
  }

  @Override
  public Set<ConversationOutcome> outcomes() {
    return Set.of(RECENCY_EXHAUSTED);
  }

  /** Resolves comparative publication wording against the latest displayed page. */
  private void resolveRecencyRefinement(
      ConversationDraft request,
      Session session,
      com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy filters) {
    if (request.getMustInclude() == null) {
      return;
    }
    String expression = request.getMustInclude().getPublicationYear();
    boolean older = ConversationText.isOlderRefinement(expression);
    boolean relative = ConversationText.isYearRefinement(expression);
    boolean answeringYear =
        session != null && session.getPendingQuestionKind() == RECENCY_YEAR_CLARIFICATION;
    if (!relative && !answeringYear) {
      return;
    }
    var previous = session == null ? null : ConversationHistory.latestSearch(session);
    var page = previous == null ? null : previous.getRecommendations();
    var years =
        page == null
            ? List.<Integer>of()
            : page.stream()
                .map(
                    com.gen3.recommenderagent.domain.recommendation.Recommendation
                        ::getPublicationYear)
                .filter(year -> year != null && year > 0 && year <= 9999)
                .toList();
    if (older) {
      resolveOlderRefinement(request, session, previous, years, filters);
    } else {
      resolveNewerRefinement(request, session, previous, years, relative, filters);
    }
  }

  /** Builds an older-publication bound and asks before crossing an inherited range. */
  private void resolveOlderRefinement(
      ConversationDraft request,
      Session session,
      ConversationDraft previous,
      List<Integer> years,
      com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy filters) {
    if (years.isEmpty()) {
      awaitRefinement(request, session, RECENCY_YEAR_CLARIFICATION);
      return;
    }
    int upper = years.stream().mapToInt(Integer::intValue).sum() / years.size() - 1;
    if (upper < 1) {
      request.setOutcome(RECENCY_EXHAUSTED);
      request.setSearchClarificationRequired(true);
      return;
    }
    var old =
        filters.yearBounds(
            previous != null && previous.getMustInclude() != null
                ? previous.getMustInclude().getPublicationYear()
                : null);
    Integer lower = old.minimum();
    boolean conflict = lower != null && lower > upper;
    String bounded =
        conflict || lower == null ? "before " + (upper + 1) : "between " + lower + " and " + upper;
    setYear(request, bounded);
    if (conflict) {
      awaitRefinement(request, session, RECENCY_RANGE_CONFIRMATION);
    }
  }

  /** Builds a newer-publication bound while respecting inherited upper limits. */
  private void resolveNewerRefinement(
      ConversationDraft request,
      Session session,
      ConversationDraft previous,
      List<Integer> years,
      boolean relative,
      com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy filters) {
    String expression = request.getMustInclude().getPublicationYear();
    Integer lower =
        relative
            ? (years.isEmpty()
                ? null
                : years.stream().mapToInt(Integer::intValue).sum() / years.size())
            : filters.yearBounds(expression).minimum();
    if (!relative && lower == null && filters.yearBounds(expression).maximum() != null) {
      return;
    }
    if (lower == null) {
      awaitRefinement(request, session, RECENCY_YEAR_CLARIFICATION);
      return;
    }
    var previousBounds =
        filters.yearBounds(
            previous == null || previous.getMustInclude() == null
                ? null
                : previous.getMustInclude().getPublicationYear());
    Integer upper = previousBounds.maximum();
    if (relative && previousBounds.minimum() != null && lower <= previousBounds.minimum()) {
      lower = previousBounds.minimum() + 1;
    }
    if (lower > 9999 || (upper != null && lower > upper)) {
      if (relative) {
        request.setOutcome(RECENCY_EXHAUSTED);
      } else {
        request.setPendingQuestion(RECENCY_RANGE_CONFIRMATION);
        session.setPendingRefinementRequest(ConversationDraft.toStoredTurn(request));
      }
      request.setSearchClarificationRequired(true);
      return;
    }
    if (!relative) {
      Integer specifiedUpper = filters.yearBounds(expression).maximum();
      if (specifiedUpper != null) {
        upper = upper == null ? specifiedUpper : Math.min(upper, specifiedUpper);
      }
    }
    String bounded = upper == null ? "from " + lower : "between " + lower + " and " + upper;
    setYear(request, bounded);
  }

  /** Retains the unfinished refinement and marks its clarification question. */
  private void awaitRefinement(
      ConversationDraft request, Session session, ConversationQuestion question) {
    request.setPendingQuestion(question);
    request.setSearchClarificationRequired(true);
    session.setPendingRefinementRequest(ConversationDraft.toStoredTurn(request));
  }

  /** Updates the effective publication year and any retained explicit search criteria. */
  private void setYear(ConversationDraft request, String value) {
    request.getMustInclude().setPublicationYear(value);
    if (request.getSearchCriteria() != null) {
      if (request.getSearchCriteria().getMustInclude() == null) {
        request.getSearchCriteria().setMustInclude(new ExactCriteria());
      }
      request.getSearchCriteria().getMustInclude().setPublicationYear(value);
    }
  }

  /** Combines a duration answer with the unfinished refinement's existing bounds. */
  private void resolveDurationAnswer(ConversationDraft request, Session session) {
    if (session == null
        || request.getMustInclude() == null
        || request.getMustInclude().getDuration() == null
        || (session.getPendingQuestionKind() != SHORTER_DURATION_CLARIFICATION
            && session.getPendingQuestionKind() != LONGER_DURATION_CLARIFICATION)
        || session.getPendingRefinementRequest() == null) {
      return;
    }
    var pending = session.getPendingRefinementRequest().getMustInclude();
    var old = DurationParser.parse(pending == null ? null : pending.getDuration());
    var supplied = DurationParser.parse(request.getMustInclude().getDuration());
    Integer minimum = supplied.minimum() == null ? old.minimum() : supplied.minimum();
    Integer maximum = supplied.maximum() == null ? old.maximum() : supplied.maximum();
    if (minimum != null && maximum != null && minimum > maximum) {
      minimum = supplied.minimum();
      maximum = supplied.maximum();
    }
    if (!java.util.Objects.equals(minimum, supplied.minimum())
        || !java.util.Objects.equals(maximum, supplied.maximum())) {
      setDuration(
          request,
          minimum == null
              ? "at most " + maximum + " minutes"
              : maximum == null
                  ? "at least " + minimum + " minutes"
                  : "between " + minimum + " and " + maximum + " minutes");
    }
    if ((old.minimum() != null && (minimum == null || minimum < old.minimum()))
        || (old.maximum() != null && (maximum == null || maximum > old.maximum()))) {
      awaitRefinement(request, session, DURATION_RANGE_CONFIRMATION);
    }
  }

  /** Updates the effective duration and any retained explicit search criteria. */
  private void setDuration(ConversationDraft request, String value) {
    var include = request.getMustInclude() == null ? new ExactCriteria() : request.getMustInclude();
    include.setDuration(value);
    request.setMustInclude(include);
    if (request.getSearchCriteria() != null) {
      if (request.getSearchCriteria().getMustInclude() == null) {
        request.getSearchCriteria().setMustInclude(new ExactCriteria());
      }
      request.getSearchCriteria().getMustInclude().setDuration(value);
    }
  }

  /** Derives shorter-or-longer bounds from the displayed page and checks inherited limits. */
  private void resolveDurationRefinement(ConversationDraft request, Session session) {
    if (!request.isShorter() && !request.isLonger()) {
      return;
    }
    var previous = session == null ? null : ConversationHistory.latestSearch(session);
    var page = previous == null ? null : previous.getRecommendations();
    boolean longer = request.isLonger();
    if (page == null
        || page.isEmpty()
        || page.stream()
            .anyMatch(
                book -> book.getDurationMinutes() == null || book.getDurationMinutes() <= 1)) {
      awaitRefinement(
          request,
          session,
          longer ? LONGER_DURATION_CLARIFICATION : SHORTER_DURATION_CLARIFICATION);
      return;
    }
    int boundary =
        longer
            ? page.stream().mapToInt(book -> book.getDurationMinutes()).max().orElseThrow()
            : page.stream().mapToInt(book -> book.getDurationMinutes()).min().orElseThrow();
    var include = request.getMustInclude() == null ? new ExactCriteria() : request.getMustInclude();
    var old = DurationParser.parse(include.getDuration());
    if (longer && boundary == Integer.MAX_VALUE) {
      throw new InvalidRequestException(
          "Could you give me a smaller minimum duration in hours or minutes?");
    }
    int next = longer ? boundary + 1 : boundary - 1;
    boolean conflict =
        longer
            ? old.maximum() != null && next > old.maximum()
            : old.minimum() != null && next < old.minimum();
    String bounded =
        longer
            ? old.maximum() == null || conflict
                ? "at least " + next + " minutes"
                : "between " + next + " and " + old.maximum() + " minutes"
            : old.minimum() == null || conflict
                ? "under " + boundary + " minutes"
                : "between " + old.minimum() + " and " + next + " minutes";
    setDuration(request, bounded);
    request.setShorter(false);
    request.setLonger(false);
    if (conflict) {
      awaitRefinement(request, session, DURATION_RANGE_CONFIRMATION);
    }
  }

  @Override
  public boolean requiresSavedPreferences(ConversationDraft request, Session session) {
    return RecommendationHandler.needsSavedPreferences(request, session);
  }

  @Override
  public boolean requiresSemanticPreferences(
      ConversationDraft request, com.gen3.recommenderagent.embedding.SemanticQueryVectors vectors) {
    return RecommendationHandler.needsSemanticPreferences(request, vectors);
  }
}
