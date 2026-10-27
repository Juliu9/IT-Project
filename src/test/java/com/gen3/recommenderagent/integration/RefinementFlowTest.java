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

  @ParameterizedTest
  @ValueSource(strings = {"3", "change count to 3", "three books per page"})
  void validCountRepliesResumeThePendingSearch(String reply) {
    var pending = request(Intent.RECOMMENDATION);
    pending.setExplicitSearch(true);
    pending.setInvalidBookCount(true);
    pending.setRawText("Recommend zero fantasy books");
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    pending.setPositiveSemanticQuery(query);
    assertThat(process(pending).getDecision()).isEqualTo(ConversationDecision.COUNT_CLARIFICATION);
    verifyNoInteractions(workflow);
    when(workflow.recommend(any(), any()))
        .thenReturn(List.of(new Recommendation("id", 1, 0.9, "Fantasy")));
    var result = service.process("session", "user", command(reply));
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getBookCount()).isEqualTo(3);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(sessions.getSession("session", "user").getPendingCountRequest()).isNull();
  }

  @Test
  void newerInvalidSearchReplacesThePendingRequestWithoutConsumingTheDisplayedBatch() {
    search(null, null);
    var before = sessions.getSession("session", "user").getPendingRecommendations();
    for (String genre : List.of("fantasy", "romance")) {
      var pending = request(Intent.RECOMMENDATION);
      pending.setRawText("Recommend zero " + genre + " books");
      pending.setExplicitSearch(true);
      pending.setInvalidBookCount(true);
      var query = new SemanticQuery();
      query.setGenres(List.of(genre));
      pending.setPositiveSemanticQuery(query);
      assertThat(process(pending).getDecision())
          .isEqualTo(ConversationDecision.COUNT_CLARIFICATION);
      assertThat(sessions.getSession("session", "user").getPendingRecommendations())
          .hasSize(before.size());
    }
    var result = service.process("session", "user", command("3"));
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("romance");
    assertThat(result.getBookCount()).isEqualTo(3);
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
    assertThat(sessions.getSession("session", "user").getPendingDecision())
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
    assertThat(sessions.getSession("session", "user").getPendingDecision()).isNull();
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
    assertThat(sessions.getSession("session", "user").getPendingDecision()).isNull();
    verify(workflow).recommend(any(), any());
  }

  @Test
  void upperBoundReplyReplacesTheUnfinishedRecencyRequest() {
    askForYearWithinPeriod();
    var result = process(yearReply("before 2000"));
    assertThat(result.isSearchClarificationRequired()).isFalse();
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("before 2000");
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(sessions.getSession("session", "user").getPendingDecision()).isNull();
    verify(workflow).recommend(any(), any());
  }

  @Test
  void conflictingStartingYearRequiresConfirmationAndSurvivesHelpAndReload() {
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
    var mapper = new ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class));
    verifyNoInteractions(workflow);
    var confirmed = service.process("session", "user", command("yes please"));
    assertThat(confirmed.isSearchClarificationRequired()).isFalse();
    assertThat(confirmed.getBookCount()).isEqualTo(5);
    assertThat(confirmed.getMustInclude().getPublicationYear()).isEqualTo("from 2010");
    assertThat(confirmed.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(sessions.getSession("session", "user").getPendingRefinementRequest()).isNull();
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
    assertThat(sessions.getSession("session", "user").getPendingRefinementRequest()).isNull();
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
  void resettingPreferencesAbandonsThePendingConfirmation() {
    askForYearWithinPeriod();
    service.process("session", "user", command("2010"));
    var count = request(Intent.CHANGE_COUNT);
    count.setBookCount(2);
    process(count);
    process(request(Intent.RESET_PREFERENCES));
    assertThat(sessions.getSession("session", "user").getPendingQuestion()).isNull();
    var result = service.process("session", "user", command("yes"));
    assertThat(result.getIntent()).isEqualTo(Intent.UNKNOWN);
    assertThat(sessions.getSession("session", "user").getBookCount()).isEqualTo(5);
    verifyNoInteractions(workflow);
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
    assertThat(sessions.getSession("session", "user").getPendingRefinementRequest()).isNull();
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
  void preferenceEditsAbandonThePendingRecencyConfirmation(
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
    assertThat(sessions.getSession("session", "user").getPendingQuestion()).isNull();
    if (answer.equals("yes")) {
      assertThat(result.getIntent()).isEqualTo(Intent.UNKNOWN);
      verifyNoInteractions(workflow);
    } else {
      assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("between 2015 and 2020");
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void genreReplacementAndAdditionHaveDifferentSearchSemantics(boolean replace) {
    search(null, null);
    var refine = request(Intent.REFINE);
    var query = new SemanticQuery();
    query.setGenres(List.of("horror"));
    refine.setPositiveSemanticQuery(query);
    if (replace) {
      refine.setReplaceSearchFields(List.of("positiveSemanticQuery.genres"));
    }
    var result = process(refine);
    assertThat(result.getPositiveSemanticQuery().getGenres())
        .containsExactlyElementsOf(replace ? List.of("horror") : List.of("fantasy", "horror"));
    assertThat(result.getSearchCriteria().getPositiveSemanticQuery().getGenres())
        .containsExactlyElementsOf(replace ? List.of("horror") : List.of("fantasy", "horror"));
  }

  @Test
  void clearingAnAuthorPersistsAcrossReloadMoreAndRefineWithoutChangingPreferences() {
    var include = new ExactCriteria();
    include.setAuthors(List.of("Suzanne Collins"));
    include.setDuration("under 5 hours");
    search(include, null);
    var preferences = new SessionPreferences();
    var preferredAuthor = new ExactCriteria();
    preferredAuthor.setAuthors(List.of("Saved Writer"));
    preferences.setMustInclude(preferredAuthor);
    var session = sessions.getSession("session", "user");
    session.setPreferences(preferences);
    sessions.updateSession(session);
    var count = request(Intent.CHANGE_COUNT);
    count.setBookCount(1);
    process(count);
    when(workflow.recommend(any(), any()))
        .thenReturn(
            List.of(
                new Recommendation("three", 1, 1.0, "Three"),
                new Recommendation("four", 2, .9, "Four")));
    var cleared = service.process("session", "user", command("any author is fine now"));
    assertThat(cleared.getMustInclude().getAuthors()).isNullOrEmpty();
    assertThat(cleared.getMustInclude().getDuration()).isEqualTo("under 5 hours");
    assertThat(cleared.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(cleared.getSearchCriteria().getMustInclude().getAuthors()).isNullOrEmpty();
    assertThat(
            sessions.getSession("session", "user").getPreferences().getMustInclude().getAuthors())
        .containsExactly("Saved Writer");
    var mapper = new ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class));
    clearInvocations(workflow);
    var more = process(request(Intent.MORE_RESULTS));
    assertThat(more.getMustInclude().getAuthors()).isNullOrEmpty();
    assertThat(more.getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("four");
    verifyNoInteractions(workflow);
    var next = request(Intent.REFINE);
    var shorter = new ExactCriteria();
    shorter.setDuration("under 3 hours");
    next.setMustInclude(shorter);
    assertThat(process(next).getMustInclude().getAuthors()).isNullOrEmpty();
    var newAuthor = request(Intent.REFINE);
    var author = new ExactCriteria();
    author.setAuthors(List.of("New Writer"));
    newAuthor.setMustInclude(author);
    assertThat(process(newAuthor).getMustInclude().getAuthors()).containsExactly("New Writer");
    var fresh = request(Intent.RECOMMENDATION);
    var genre = new SemanticQuery();
    genre.setGenres(List.of("mystery"));
    fresh.setPositiveSemanticQuery(genre);
    fresh.setExplicitSearch(true);
    assertThat(process(fresh).getMustInclude().getAuthors()).containsExactly("Saved Writer");
  }

  @Test
  void replacingAGenreSuppressesSavedGenreButKeepsOtherSavedPreferences() {
    search(null, null);
    var prefs = new SessionPreferences();
    var old = new SemanticQuery();
    old.setGenres(List.of("romance"));
    old.setNarrators(List.of("Stephen Fry"));
    prefs.setPositiveSemanticQuery(old);
    var session = sessions.getSession("session", "user");
    session.setPreferences(prefs);
    sessions.updateSession(session);
    var change = request(Intent.REFINE);
    var horror = new SemanticQuery();
    horror.setGenres(List.of("horror"));
    change.setPositiveSemanticQuery(horror);
    change.setReplaceSearchFields(List.of("positiveSemanticQuery.genres"));
    var replaced = process(change);
    assertThat(replaced.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(replaced.getPositiveSemanticQuery().getNarrators()).containsExactly("Stephen Fry");
    var also = request(Intent.REFINE);
    var mystery = new SemanticQuery();
    mystery.setGenres(List.of("mystery"));
    also.setPositiveSemanticQuery(mystery);
    assertThat(process(also).getPositiveSemanticQuery().getGenres())
        .containsExactly("horror", "mystery");
    assertThat(
            sessions
                .getSession("session", "user")
                .getPreferences()
                .getPositiveSemanticQuery()
                .getGenres())
        .containsExactly("romance");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "any narrator,mustInclude.narrators",
    "remove the time limit,mustInclude.duration",
    "any publication year,mustInclude.publicationYear"
  })
  void clearingOneFilterPreservesAllUnrelatedCriteria(String text, String field) {
    var include = new ExactCriteria();
    include.setAuthors(List.of("Suzanne Collins"));
    include.setNarrators(List.of("Stephen Fry"));
    include.setDuration("under 5 hours");
    include.setPublicationYear("from 2000");
    search(include, null);
    var result = service.process("session", "user", command(text));
    var actual = result.getMustInclude();
    assertThat(actual.getAuthors()).containsExactly("Suzanne Collins");
    assertThat(actual.getNarrators())
        .isEqualTo(field.endsWith("narrators") ? null : List.of("Stephen Fry"));
    assertThat(actual.getDuration()).isEqualTo(field.endsWith("duration") ? null : "under 5 hours");
    assertThat(actual.getPublicationYear())
        .isEqualTo(field.endsWith("publicationYear") ? null : "from 2000");
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
  }

  @Test
  void clearingAFilterWithoutHistoryAsksForSearchContent() {
    var result = service.process("session", "user", command("remove the time limit"));
    assertThat(result.isSearchClarificationRequired()).isTrue();
    verifyNoInteractions(workflow);
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
    session.setPendingDecision(
        com.gen3.recommenderagent.domain.session.ConversationDecision
            .PREFERENCE_UPDATE_CLARIFICATION);
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

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource(
      delimiter = '|',
      value = {
        "under 3 hours|under 3 hours|SHORTER_DURATION_CLARIFICATION",
        "5 hours|at most 5 hours|SHORTER_DURATION_CLARIFICATION",
        "5|at most 5 hours|SHORTER_DURATION_CLARIFICATION",
        "five|at most 5 hours|SHORTER_DURATION_CLARIFICATION",
        "2 and a half hours|at most 2.5 hours|SHORTER_DURATION_CLARIFICATION",
        "half an hour|at most 0.5 hour|SHORTER_DURATION_CLARIFICATION",
        "an hour and a half|at most 1.5 hour|SHORTER_DURATION_CLARIFICATION",
        "two hours and a half|at most 2.5 hours|SHORTER_DURATION_CLARIFICATION",
        "three quarters of an hour|at most 0.75 hour|SHORTER_DURATION_CLARIFICATION",
        "one hundred minutes|at most 100 minutes|SHORTER_DURATION_CLARIFICATION",
        "a couple of hours|at most 2 hours|SHORTER_DURATION_CLARIFICATION",
        "a hundred minutes|at most 100 minutes|SHORTER_DURATION_CLARIFICATION",
        "two thousand minutes|at most 2000 minutes|SHORTER_DURATION_CLARIFICATION",
        "2 hours 30|at most 2 hours 30 minutes|SHORTER_DURATION_CLARIFICATION",
        "a couple of hours|at least 2 hours|LONGER_DURATION_CLARIFICATION",
        "under a couple of hours|under 2 hours|SHORTER_DURATION_CLARIFICATION",
        "two thousand three hundred and twenty minutes|at most 2320 minutes|SHORTER_DURATION_CLARIFICATION",
        "exactly an hour and a half|exactly 1.5 hour|LONGER_DURATION_CLARIFICATION",
        ".5 hours|at most .5 hours|SHORTER_DURATION_CLARIFICATION",
        "between two and three hours|between 2 and 3 hours|SHORTER_DURATION_CLARIFICATION",
        "one hour and thirty minutes|at most 1 hour and 30 minutes|SHORTER_DURATION_CLARIFICATION",
        "exactly 2.5 hours|exactly 2.5 hours|SHORTER_DURATION_CLARIFICATION",
        "two and a half hours|at least 2.5 hours|LONGER_DURATION_CLARIFICATION",
        "5|at least 5 hours|LONGER_DURATION_CLARIFICATION",
        "over 5 hours|over 5 hours|LONGER_DURATION_CLARIFICATION"
      })
  void durationOnlyAnswersResumeThePendingSearchDespiteModelSearchFlags(
      String text, String duration, ConversationDecision question) throws Exception {
    search(null, null);
    var initial =
        service.process(
            "session",
            "user",
            command(
                question == ConversationDecision.SHORTER_DURATION_CLARIFICATION
                    ? "make them shorter"
                    : "make them longer"));
    assertThat(initial.getDecision()).isEqualTo(question);
    var mapper = new ObjectMapper();
    var session =
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class);
    sessions.updateSession(session);
    var reply = request(Intent.RECOMMENDATION);
    reply.setRawText(text);
    reply.setExplicitSearch(true);
    reply.setBookCount(5);
    var invented = new SemanticQuery();
    invented.setGenres(List.of("mystery"));
    reply.setPositiveSemanticQuery(invented);
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    var refined = process(reply);
    assertThat(refined.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(refined.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(refined.getMustInclude().getDuration()).isEqualTo(duration);
    assertThat(refined.getDecision()).isEqualTo(ConversationDecision.NO_MATCHES);
    assertThat(sessions.getSession("session", "user").getPendingDecision())
        .isEqualTo(ConversationDecision.SEARCH_CHANGE_OFFER);
    String response =
        new com.gen3.recommenderagent.response.ConversationResponseGenerator(
                new com.gen3.recommenderagent.response.AiResponseGenerator(null))
            .generate(refined.getRecommendations(), refined);
    assertThat(response)
        .contains("matching those criteria")
        .doesNotContain("good match for " + text);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"about two hours", "two or three hours", "a few hours"})
  void unclearDurationAnswersRepeatTheQuestionWithoutStartingANewSearch(String text) {
    search(null, null);
    service.process("session", "user", shorterCommand());
    clearInvocations(workflow);
    var reply = request(Intent.RECOMMENDATION);
    reply.setRawText(text);
    reply.setExplicitSearch(true);
    var limit = new ExactCriteria();
    limit.setDuration("2 hours");
    reply.setMustInclude(limit);
    var result = process(reply);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.SHORTER_DURATION_CLARIFICATION);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(result.getRecommendations()).isEmpty();
    verifyNoInteractions(workflow);
    var valid = service.process("session", "user", command("2"));
    assertThat(valid.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(valid.getMustInclude().getDuration()).isEqualTo("at most 2 hours");
    assertThat(valid.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    verify(workflow).recommend(any(), any());
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
        .hasMessageContaining("Please restate your full request");
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
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationTurn.fromDraft(pending));
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
