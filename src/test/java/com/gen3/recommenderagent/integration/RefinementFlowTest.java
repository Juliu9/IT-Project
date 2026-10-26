package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class RefinementFlowTest {
  private final RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
  private final RecommendationFlowTestSupport.InMemorySessionRepository sessions =
      new RecommendationFlowTestSupport.InMemorySessionRepository();
  private final SessionTransitionService service =
      ConversationTestSupport.transitions(sessions, workflow);

  private ConversationDraft request(Intent intent) {
    var request = new ConversationDraft();
    request.setIntent(intent);
    return request;
  }

  private ConversationTurn process(ConversationDraft request) {
    return service.process("session", "user", request);
  }

  private void search(ExactCriteria include, ExactCriteria exclude) {
    when(workflow.recommend(any(), any()))
        .thenReturn(
            List.of(
                new Recommendation("one", 1, 1.0, "One"),
                new Recommendation("two", 2, 0.9, "Two")));
    var request = request(Intent.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    request.setPositiveSemanticQuery(query);
    request.setBookCount(1);
    request.setMustInclude(include);
    request.setMustNotInclude(exclude);
    process(request);
    clearInvocations(workflow);
  }

  private com.gen3.recommenderagent.domain.session.SessionRequest shorterCommand() {
    return command("make them shorter");
  }

  private com.gen3.recommenderagent.domain.session.SessionRequest command(String text) {
    return new com.gen3.recommenderagent.inputparser.InputParser(
            new com.gen3.recommenderagent.inputparser.CommandParser(),
            new com.gen3.recommenderagent.inputparser.AiParser(
                mock(org.springframework.ai.chat.client.ChatClient.class),
                com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
        .parse(text);
  }

  @Test
  void moreRecentUsesTheFlooredPageAverageAndPreservesThePeriodUpperBoundAfterReload() {
    var years = List.of(1960, 1975, 1980, 1990, 2005);
    var books =
        java.util.stream.IntStream.range(0, years.size())
            .mapToObj(
                i -> {
                  var book = new Recommendation("dated-" + i, i + 1, 1.0, "Book " + i);
                  book.setPublicationYear(years.get(i));
                  return book;
                })
            .toList();
    when(workflow.recommend(any(), any())).thenReturn(books, List.of());
    var original = request(Intent.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    original.setPositiveSemanticQuery(query);
    var include = new ExactCriteria();
    include.setAuthors(List.of("Suzanne Collins"));
    include.setPublicationYear("1950 to 2000s");
    original.setMustInclude(include);
    process(original);
    var mapper = new ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class));
    var result = service.process("session", "user", command("more recent"));
    assertThat(result.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(result.getMustInclude().getAuthors()).containsExactly("Suzanne Collins");
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("between 1982 and 2009");
    assertThat(result.getSearchCriteria().getMustInclude().getPublicationYear())
        .isEqualTo("between 1982 and 2009");
    assertThat(sessions.getSession("session", "user").getPreferences()).isNull();
  }

  @Test
  void moreRecentIgnoresUnknownYearsWhenSomeDisplayedYearsAreKnown() {
    var dated = new Recommendation("dated", 1, 1.0, "Dated");
    dated.setPublicationYear(2000);
    var second = new Recommendation("second", 2, 1.0, "Second");
    second.setPublicationYear(2005);
    var unknown = new Recommendation("unknown", 3, 1.0, "Unknown");
    when(workflow.recommend(any(), any())).thenReturn(List.of(dated, second, unknown), List.of());
    var original = request(Intent.RECOMMENDATION);
    var include = new ExactCriteria();
    include.setPublicationYear("1950 to 2000s");
    original.setMustInclude(include);
    process(original);
    var result = service.process("session", "user", command("more recent"));
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("between 2002 and 2009");
  }

  @Test
  void unknownDisplayedYearsAskForABoundaryWithoutConsumingHistoryOrCachedBooks() {
    search(null, null);
    var before = sessions.getSession("session", "user");
    var pendingIds = Recommendation.bookIds(before.getPendingRecommendations());
    var question = service.process("session", "user", command("more recent"));
    assertThat(question.getDecision()).isEqualTo(ConversationDecision.RECENCY_YEAR_CLARIFICATION);
    assertThat(sessions.getSession("session", "user").getPendingQuestion())
        .isEqualTo(ConversationDecision.RECENCY_YEAR_CLARIFICATION);
    assertThat(
            Recommendation.bookIds(
                sessions.getSession("session", "user").getPendingRecommendations()))
        .isEqualTo(pendingIds);
    verifyNoInteractions(workflow);
    var mapper = new ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class));
    var resumed = service.process("session", "user", command("2000"));
    assertThat(resumed.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(resumed.getMustInclude().getPublicationYear()).isEqualTo("from 2000");
    assertThat(resumed.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(sessions.getSession("session", "user").getPendingQuestion()).isNull();
    verify(workflow).recommend(any(), any());
  }

  private void askForYearWithinPeriod() {
    var include = new ExactCriteria();
    include.setPublicationYear("between 1950 and 2009");
    search(include, null);
    service.process("session", "user", command("more recent"));
    clearInvocations(workflow);
  }

  private ConversationDraft yearReply(String expression) {
    var reply = request(Intent.UNKNOWN);
    reply.setRawText(expression);
    var include = new ExactCriteria();
    include.setPublicationYear(expression);
    reply.setMustInclude(include);
    return reply;
  }

  @Test
  void unrelatedRefinementAbandonsTheYearQuestion() {
    askForYearWithinPeriod();
    var refine = request(Intent.REFINE);
    var include = new ExactCriteria();
    include.setDuration("under 5 hours");
    refine.setMustInclude(include);
    var result = process(refine);
    assertThat(result.isSearchClarificationRequired()).isFalse();
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 5 hours");
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("between 1950 and 2009");
    assertThat(sessions.getSession("session", "user").getPendingQuestion()).isNull();
    verify(workflow).recommend(any(), any());
  }

  @Test
  void upperBoundReplyReplacesTheUnfinishedRecencyRequest() {
    askForYearWithinPeriod();
    var result = process(yearReply("before 2000"));
    assertThat(result.isSearchClarificationRequired()).isFalse();
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("before 2000");
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(sessions.getSession("session", "user").getPendingQuestion()).isNull();
    verify(workflow).recommend(any(), any());
  }

  @Test
  void conflictingStartingYearRequiresConfirmationAndSurvivesHelpCountAndReload() {
    askForYearWithinPeriod();
    var pending =
        Recommendation.bookIds(sessions.getSession("session", "user").getPendingRecommendations());
    var question = service.process("session", "user", command("2010"));
    assertThat(question.getDecision()).isEqualTo(ConversationDecision.RECENCY_RANGE_CONFIRMATION);
    assertThat(
            Recommendation.bookIds(
                sessions.getSession("session", "user").getPendingRecommendations()))
        .isEqualTo(pending);
    process(request(Intent.HELP));
    var count = request(Intent.CHANGE_COUNT);
    count.setBookCount(2);
    process(count);
    var mapper = new ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class));
    verifyNoInteractions(workflow);
    var confirmed = service.process("session", "user", command("yes please"));
    assertThat(confirmed.isSearchClarificationRequired()).isFalse();
    assertThat(confirmed.getBookCount()).isEqualTo(2);
    assertThat(confirmed.getMustInclude().getPublicationYear()).isEqualTo("from 2010");
    assertThat(confirmed.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(sessions.getSession("session", "user").getPendingRecencyRequest()).isNull();
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"no", "no thanks", "not now"})
  void decliningTheYearExtensionPreservesTheOriginalBatch(String answer) {
    askForYearWithinPeriod();
    var pending =
        Recommendation.bookIds(sessions.getSession("session", "user").getPendingRecommendations());
    service.process("session", "user", command("2010"));
    var result = service.process("session", "user", command(answer));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.CANCELLED);
    assertThat(
            Recommendation.bookIds(
                sessions.getSession("session", "user").getPendingRecommendations()))
        .isEqualTo(pending);
    assertThat(sessions.getSession("session", "user").getPendingRecencyRequest()).isNull();
    var repeated = process(request(Intent.REPEAT_RECOMMENDATIONS));
    assertThat(repeated.getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("one");
    verifyNoInteractions(workflow);
  }

  @Test
  void confirmationPreservesTheRequestedEndYearAfterReload() {
    askForYearWithinPeriod();
    var question = process(yearReply("between 2010 and 2020"));
    assertThat(question.getDecision()).isEqualTo(ConversationDecision.RECENCY_RANGE_CONFIRMATION);
    var mapper = new ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class));
    verifyNoInteractions(workflow);
    var result = service.process("session", "user", command("yes"));
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("between 2010 and 2020");
    assertThat(result.getSearchCriteria().getMustInclude().getPublicationYear())
        .isEqualTo("between 2010 and 2020");
    verify(workflow).recommend(any(), any());
  }

  @Test
  void resettingPreferencesUpdatesTheCountInThePendingConfirmation() {
    askForYearWithinPeriod();
    service.process("session", "user", command("2010"));
    var count = request(Intent.CHANGE_COUNT);
    count.setBookCount(2);
    process(count);
    process(request(Intent.RESET_PREFERENCES));
    assertThat(sessions.getSession("session", "user").getPendingQuestion())
        .isEqualTo(ConversationDecision.RECENCY_RANGE_CONFIRMATION);
    var result = service.process("session", "user", command("yes"));
    assertThat(result.getBookCount()).isEqualTo(5);
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("from 2010");
    assertThat(sessions.getSession("session", "user").getBookCount()).isEqualTo(5);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2000.", "2000!", "2000?", "2000, please"})
  void bareYearAnswersAcceptPunctuationAndCourtesy(String text) {
    askForYearWithinPeriod();
    var result = service.process("session", "user", command(text));
    assertThat(result.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("between 2000 and 2009");
    assertThat(result.isSearchClarificationRequired()).isFalse();
    verify(workflow).recommend(any(), any());
  }

  @Test
  void aBareReplacementYearAcceptsPunctuationDuringConfirmation() {
    askForYearWithinPeriod();
    service.process("session", "user", command("2010"));
    var result = service.process("session", "user", command("2015."));
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("from 2015");
    assertThat(result.isSearchClarificationRequired()).isFalse();
    verify(workflow).recommend(any(), any());
  }

  @Test
  void alternativeRangeReplacesTheProposedYearExtension() {
    askForYearWithinPeriod();
    service.process("session", "user", command("2010"));
    var result = process(yearReply("between 2015 and 2020"));
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("between 2015 and 2020");
    assertThat(result.isSearchClarificationRequired()).isFalse();
    verify(workflow).recommend(any(), any());
  }

  @Test
  void aNewSearchWithAYearDoesNotAnswerThePendingConfirmation() {
    askForYearWithinPeriod();
    service.process("session", "user", command("2010"));
    var fresh = yearReply("between 2020 and 2025");
    fresh.setIntent(Intent.RECOMMENDATION);
    fresh.setExplicitSearch(true);
    var query = new SemanticQuery();
    query.setGenres(List.of("horror"));
    fresh.setPositiveSemanticQuery(query);
    var result = process(fresh);
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("between 2020 and 2025");
    assertThat(sessions.getSession("session", "user").getPendingRecencyRequest()).isNull();
  }

  @Test
  void aReplacementRangeKeepsTheOtherProposedConstraints() {
    askForYearWithinPeriod();
    var proposal = yearReply("from 2010");
    proposal.getMustInclude().setDuration("under 5 hours");
    assertThat(process(proposal).getDecision())
        .isEqualTo(ConversationDecision.RECENCY_RANGE_CONFIRMATION);
    var result = process(yearReply("between 2015 and 2020"));
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("between 2015 and 2020");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 5 hours");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "RESET_PREFERENCES,false,yes",
    "UPDATE_PREFERENCES,false,yes",
    "RESET_PREFERENCES,false,range",
    "UPDATE_PREFERENCES,false,range",
    "RESET_PREFERENCES,true,yes",
    "UPDATE_PREFERENCES,true,yes"
  })
  void confirmingRecencyReappliesCurrentPreferencesAndPreservesExplicitConstraints(
      Intent action, boolean explicitAuthor, String answer) {
    var initial = request(Intent.RECOMMENDATION);
    initial.setPersonalised(true);
    var include = new ExactCriteria();
    include.setPublicationYear("between 1950 and 2009");
    if (explicitAuthor) {
      include.setAuthors(List.of("Explicit Writer"));
    }
    initial.setMustInclude(include);
    when(workflow.recommend(any(), any()))
        .thenReturn(List.of(new Recommendation("old", 1, 1.0, "Old")));
    var preferences = new SessionPreferences();
    var inherited = new ExactCriteria();
    inherited.setAuthors(List.of("Old Writer"));
    preferences.setMustInclude(inherited);
    var genre = new SemanticQuery();
    genre.setGenres(List.of("fantasy"));
    preferences.setPositiveSemanticQuery(genre);
    var save = request(Intent.UPDATE_PREFERENCES);
    save.setPreferenceUpdates(preferences);
    process(save);
    process(initial);
    service.process("session", "user", command("more recent"));
    service.process("session", "user", command("2010"));
    var edit = request(action);
    if (action == Intent.UPDATE_PREFERENCES) {
      var updates = new SessionPreferences();
      var replacement = new ExactCriteria();
      replacement.setAuthors(List.of("New Writer"));
      updates.setMustInclude(replacement);
      var newGenre = new SemanticQuery();
      newGenre.setGenres(List.of("horror"));
      updates.setPositiveSemanticQuery(newGenre);
      edit.setPreferenceUpdates(updates);
      edit.setReplacePreferenceFields(
          List.of("mustInclude.authors", "positiveSemanticQuery.genres"));
    }
    process(edit);
    clearInvocations(workflow);
    var result =
        answer.equals("yes")
            ? service.process("session", "user", command("yes"))
            : process(yearReply("between 2015 and 2020"));
    assertThat(result.isSearchClarificationRequired()).isFalse();
    if (explicitAuthor) {
      assertThat(result.getMustInclude().getAuthors()).containsExactly("Explicit Writer");
      assertThat(result.getSearchCriteria().getMustInclude().getAuthors())
          .containsExactly("Explicit Writer");
    } else {
      assertThat(result.getSearchCriteria().getMustInclude().getAuthors()).isNullOrEmpty();
      if (action == Intent.RESET_PREFERENCES) {
        assertThat(result.getMustInclude().getAuthors()).isNullOrEmpty();
      } else {
        assertThat(result.getMustInclude().getAuthors()).containsExactly("New Writer");
      }
    }
    if (action == Intent.RESET_PREFERENCES) {
      assertThat(result.getPositiveSemanticQuery()).isNull();
    } else {
      assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    }
    verify(workflow).recommend(any(), any());
  }

  @Test
  void unchangedAverageAdvancesOneYearAndExhaustionPreservesThePageAndQueue() {
    var include = new ExactCriteria();
    include.setPublicationYear("between 2005 and 2005");
    search(include, null);
    var session = sessions.getSession("session", "user");
    session
        .getRequests()
        .getFirst()
        .getRecommendations()
        .forEach(book -> book.setPublicationYear(2005));
    sessions.updateSession(session);
    var pending = Recommendation.bookIds(session.getPendingRecommendations());
    var exhausted = service.process("session", "user", command("more recent"));
    assertThat(exhausted.getDecision()).isEqualTo(ConversationDecision.RECENCY_EXHAUSTED);
    assertThat(process(request(Intent.REPEAT)).getDecision())
        .isEqualTo(ConversationDecision.RECENCY_EXHAUSTED);
    assertThat(process(request(Intent.REPEAT_RECOMMENDATIONS)).getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("one");
    assertThat(
            Recommendation.bookIds(
                sessions.getSession("session", "user").getPendingRecommendations()))
        .isEqualTo(pending);
    verifyNoInteractions(workflow);
    var more = request(Intent.MORE_RESULTS);
    more.setBookCount(1);
    assertThat(process(more).getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("two");
    verifyNoInteractions(workflow);
  }

  @Test
  void unchangedAverageAdvancesTheLowerBoundWhenThePeriodAllowsIt() {
    var include = new ExactCriteria();
    include.setPublicationYear("between 2005 and 2010");
    search(include, null);
    var session = sessions.getSession("session", "user");
    session
        .getRequests()
        .getFirst()
        .getRecommendations()
        .forEach(book -> book.setPublicationYear(2005));
    sessions.updateSession(session);
    var result = service.process("session", "user", command("more recent"));
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("between 2006 and 2010");
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"fresh", "cleared", "new-session"})
  void moreRecentWithoutSearchContextAsksForContent(String scenario) {
    if (!scenario.equals("fresh")) {
      search(null, null);
      if (scenario.equals("cleared")) {
        process(request(Intent.CLEAR_HISTORY));
      }
      clearInvocations(workflow);
    }
    var result =
        service.process(
            scenario.equals("new-session") ? "new-session" : "session",
            "user",
            command("more recent"));
    assertThat(result.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.SEARCH_CLARIFICATION);
    assertThat(result.getRecommendations()).isEmpty();
    verifyNoInteractions(workflow);
  }

  @Test
  void shorterFollowUpKeepsScienceFictionAndUsesDisplayedDurationsWithoutEditingPreferences() {
    var old = new Recommendation("old", 1, 1.0, "Science Fiction");
    old.setDurationMinutes(600);
    var next = new Recommendation("next", 1, 1.0, "Short Science Fiction");
    next.setDurationMinutes(120);
    when(workflow.recommend(any(), any())).thenReturn(List.of(old), List.of(next));
    var search = request(Intent.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setGenres(List.of("science fiction"));
    search.setPositiveSemanticQuery(query);
    process(search);
    var session = sessions.getSession("session", "user");
    session.setAwaitingPreferenceUpdate(true);
    session.setLastDecision(ConversationDecision.PREFERENCE_UPDATE_CLARIFICATION);
    sessions.updateSession(session);
    var result = service.process("session", "user", shorterCommand());
    assertThat(result.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("science fiction");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 600 minutes");
    assertThat(result.getSearchCriteria().getMustInclude().getDuration())
        .isEqualTo("under 600 minutes");
    assertThat(result.getRecommendations().getFirst().getDurationMinutes()).isLessThan(600);
    assertThat(sessions.getSession("session", "user").getPreferences()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"fresh", "cleared", "new-session"})
  void shorterWithoutAnActiveSearchAsksForContentWithoutRetrieval(String scenario) {
    if (!scenario.equals("fresh")) {
      search(null, null);
      if (scenario.equals("cleared")) {
        process(request(Intent.CLEAR_HISTORY));
      }
      clearInvocations(workflow);
    }
    String id = scenario.equals("new-session") ? "new-session" : "session";
    var result = service.process(id, "user", shorterCommand());
    assertThat(result.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.SEARCH_CLARIFICATION);
    assertThat(result.getPositiveSemanticQuery()).isNull();
    assertThat(result.getMustInclude()).isNull();
    assertThat(result.getRecommendations()).isEmpty();
    verifyNoInteractions(workflow);
  }

  @Test
  void oldRecommendationsWithoutDurationsAskForALimitAndKeepTheSearch() {
    search(null, null);
    var result = service.process("session", "user", shorterCommand());
    assertThat(result.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.SHORTER_DURATION_CLARIFICATION);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(result.getMustInclude() == null || result.getMustInclude().getDuration() == null)
        .isTrue();
    verifyNoInteractions(workflow);
    var reply = request(Intent.RECOMMENDATION);
    reply.setExplicitSearch(false);
    var limit = new ExactCriteria();
    limit.setDuration("under 2 hours");
    reply.setMustInclude(limit);
    var refined = process(reply);
    assertThat(refined.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(refined.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(refined.getMustInclude().getDuration()).isEqualTo("under 2 hours");
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void replacesOrAddsExactAuthorsAndNarrators(boolean add) {
    var previous = new ExactCriteria();
    previous.setAuthors(List.of("Author A"));
    previous.setNarrators(List.of("Narrator A"));
    previous.setLanguage("English");
    search(previous, null);
    var refinement = request(Intent.REFINE);
    var include = new ExactCriteria();
    include.setAuthors(List.of("Author B"));
    include.setNarrators(List.of("Narrator B"));
    refinement.setMustInclude(include);
    refinement.setAddAuthors(add);
    refinement.setAddNarrators(add);
    var result = process(refinement);
    assertThat(result.getMustInclude().getAuthors())
        .containsExactlyElementsOf(add ? List.of("Author A", "Author B") : List.of("Author B"));
    assertThat(result.getMustInclude().getNarrators())
        .containsExactlyElementsOf(
            add ? List.of("Narrator A", "Narrator B") : List.of("Narrator B"));
    assertThat(result.getMustInclude().getLanguage()).isEqualTo("English");
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
  }

  @Test
  void explicitExclusionOverridesInheritedRequirement() {
    var include = new ExactCriteria();
    include.setLanguage("English");
    search(include, null);
    var refinement = request(Intent.REFINE);
    var exclude = new ExactCriteria();
    exclude.setLanguage("English");
    refinement.setMustNotInclude(exclude);
    var result = process(refinement);
    assertThat(result.getMustInclude().getLanguage()).isNull();
    assertThat(result.getMustNotInclude().getLanguage()).isEqualTo("English");
    verify(workflow).recommend(any(), any());
  }

  @Test
  void explicitRequirementOverridesInheritedExclusionWithoutDroppingOtherAuthors() {
    var exclude = new ExactCriteria();
    exclude.setAuthors(List.of("Author A", "Author B"));
    search(null, exclude);
    var refinement = request(Intent.REFINE);
    var include = new ExactCriteria();
    include.setAuthors(List.of("Author A"));
    refinement.setMustInclude(include);
    var result = process(refinement);
    assertThat(result.getMustInclude().getAuthors()).containsExactly("Author A");
    assertThat(result.getMustNotInclude().getAuthors()).containsExactly("Author B");
  }

  @Test
  void conflictPreservesSearchQueueAndSessionVersionWithoutCallingWorkflow() throws Exception {
    search(null, null);
    var mapper = new ObjectMapper();
    String before = mapper.writeValueAsString(sessions.getSession("session", "user"));
    var refinement = request(Intent.REFINE);
    var include = new ExactCriteria();
    include.setLanguage("English");
    var exclude = new ExactCriteria();
    exclude.setLanguage("English");
    refinement.setMustInclude(include);
    refinement.setMustNotInclude(exclude);
    assertThatThrownBy(() -> process(refinement))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("Which requirement");
    assertThat(mapper.writeValueAsString(sessions.getSession("session", "user"))).isEqualTo(before);
    verifyNoInteractions(workflow);
  }

  @Test
  void partialAuthorExclusionAndSemanticMixturesRemainValid() {
    var request = request(Intent.RECOMMENDATION);
    var include = new ExactCriteria();
    include.setAuthors(List.of("A", "B"));
    var exclude = new ExactCriteria();
    exclude.setAuthors(List.of("A"));
    request.setMustInclude(include);
    request.setMustNotInclude(exclude);
    var query = new SemanticQuery();
    query.setGenres(List.of("romance", "horror"));
    request.setPositiveSemanticQuery(query);
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    assertThat(process(request).getPositiveSemanticQuery().getGenres())
        .containsExactly("romance", "horror");
    verify(workflow).recommend(any(), any());
  }

  @Test
  void freshVagueRefinementClarifiesEvenWithSavedPreferences() {
    var session = new Session();
    session.setSessionId("session");
    session.setUserId("user");
    var preferences = new SessionPreferences();
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    preferences.setPositiveSemanticQuery(query);
    session.setPreferences(preferences);
    sessions.updateSession(session);
    var request = request(Intent.REFINE);
    request.setRawText("make it shorter");
    assertThat(process(request).getDecision()).isEqualTo(ConversationDecision.SEARCH_CLARIFICATION);
    verifyNoInteractions(workflow);
    assertThat(sessions.getSession("session", "user").getRequests()).isEmpty();
  }

  @Test
  void moreDoesNotReintroduceASavedPreferenceThatTheSearchCorrected() {
    var session = new Session();
    session.setSessionId("session");
    session.setUserId("user");
    var preferences = new SessionPreferences();
    var defaults = new ExactCriteria();
    defaults.setLanguage("English");
    preferences.setMustInclude(defaults);
    session.setPreferences(preferences);
    sessions.updateSession(session);
    search(null, null);
    var correction = request(Intent.REFINE);
    var exclude = new ExactCriteria();
    exclude.setLanguage("English");
    correction.setMustNotInclude(exclude);
    assertThat(process(correction).getMustInclude().getLanguage()).isNull();
    var more = process(request(Intent.MORE_RESULTS));
    assertThat(more.getMustInclude().getLanguage()).isNull();
    assertThat(more.getMustNotInclude().getLanguage()).isEqualTo("English");
  }

  @Test
  void standaloneRecommendationCanAnswerThePreferenceQuestion() {
    var session = new Session();
    session.setSessionId("session");
    session.setUserId("user");
    var pending = request(Intent.RECOMMENDATION);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setRequests(new java.util.ArrayList<>(List.of(ConversationTurn.fromDraft(pending))));
    sessions.updateSession(session);
    var reply = request(Intent.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    reply.setPositiveSemanticQuery(query);
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    assertThat(process(reply).getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(
            sessions
                .getSession("session", "user")
                .getPreferences()
                .getPositiveSemanticQuery()
                .getGenres())
        .containsExactly("fantasy");
  }

  @Test
  void standaloneCriteriaRouteToRecommendationInFreshSession() {
    var request = request(Intent.REFINE);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    request.setPositiveSemanticQuery(query);
    var include = new ExactCriteria();
    include.setDuration("under 5 hours");
    request.setMustInclude(include);
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    var result = process(request);
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.isSearchClarificationRequired()).isFalse();
    verify(workflow).recommend(any(), any());
  }
}
