package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.AudiobookRecommendationWorkflow;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.embedding.SemanticQueryVectorService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.inputparser.AiParser;
import com.gen3.recommenderagent.inputparser.CommandParser;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import com.gen3.recommenderagent.retrieval.CandidateRetriever;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class PreferenceUpdateFlowTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "change count to 3 at a time",
        "only three at a time",
        "3 per page",
        "change count to three books"
      })
  void countCommandsStillWorkWhileAPreferenceEditQuestionIsPending(String command) {
    process(update(tastes("romance")));
    process(request(ConversationAction.UPDATE_PREFERENCES));
    var ai = mock(AiParser.class);
    var parser = new InputParser(new CommandParser(), ai);
    var first = service.process("session", "user", parser.parse(command));
    assertThat(first.getDecision()).isEqualTo(ConversationDecision.COUNT_CHANGED);
    assertThat(sessions.getSession("session", "user").getBookCount()).isEqualTo(3);
    var second = service.process("session", "user", parser.parse("4 books at a time"));
    assertThat(second.getDecision()).isEqualTo(ConversationDecision.COUNT_CHANGED);
    assertThat(sessions.getSession("session", "user").getBookCount()).isEqualTo(4);
    assertThat(
            sessions
                .getSession("session", "user")
                .getPreferences()
                .getPositiveSemanticQuery()
                .getGenres())
        .containsExactly("romance");
    verifyNoInteractions(ai, vectors, retriever, ranker);
  }

  private final CandidateRetriever retriever = mock(CandidateRetriever.class);
  private final Ranker ranker = mock(Ranker.class);
  private final SemanticQueryVectorService vectors = mock(SemanticQueryVectorService.class);
  private final UserPreferenceVectorRepository users = mock(UserPreferenceVectorRepository.class);
  private final RecommendationFlowTestSupport.InMemorySessionRepository sessions =
      new RecommendationFlowTestSupport.InMemorySessionRepository();
  private final SessionTransitionService service =
      ConversationTestSupport.transitions(
          sessions, new AudiobookRecommendationWorkflow(retriever, ranker, vectors, users));

  PreferenceUpdateFlowTest() throws Exception {
    when(users.findEmbeddingByUserId("user")).thenReturn(Optional.empty());
    var encoded = new SemanticQueryVectors(List.of(new float[] {1, 0}), List.of());
    when(vectors.create(any())).thenReturn(encoded);
    when(retriever.getCandidates(any(), any(), eq(50), anySet())).thenReturn(List.of());
    var next = new java.util.concurrent.atomic.AtomicInteger();
    when(ranker.rank(anyList(), anyInt(), any(RankingContext.class)))
        .thenAnswer(
            invocation -> {
              int start = next.getAndAdd(20) + 1;
              return IntStream.range(start, start + 20)
                  .mapToObj(i -> new Recommendation("book-" + i, i - start + 1, 1.0, "Title " + i))
                  .toList();
            });
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void moreAfterResetRefillsTheFrozenSearchWithoutLosingTheCachedTail(boolean refill)
      throws Exception {
    if (!refill) {
      when(ranker.rank(anyList(), anyInt(), any(RankingContext.class)))
          .thenReturn(
              IntStream.rangeClosed(1, 20)
                  .mapToObj(i -> new Recommendation("book-" + i, i, 1.0, "Title " + i))
                  .toList(),
              List.of());
    }
    process(update(tastes("horror")));
    var count = request(ConversationAction.CHANGE_COUNT);
    count.setBookCount(2);
    process(count);
    var search = request(ConversationAction.RECOMMENDATION);
    search.setPersonalised(true);
    process(search);
    process(request(ConversationAction.RESET_PREFERENCES));
    for (int page = 0; page < 3; page++) {
      process(request(ConversationAction.MORE_RESULTS));
    }
    var result = process(request(ConversationAction.MORE_RESULTS));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(result.isPreferenceClarificationRequired()).isFalse();
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactlyElementsOf(
            refill
                ? List.of("book-18", "book-19", "book-20", "book-21", "book-22")
                : List.of("book-18", "book-19", "book-20"));
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(sessions.getSession("session", "user").getPreferences()).isNull();
    verify(users).findEmbeddingByUserId("user");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"more", "another", "2 more", "another five"})
  void moreAbandonsTheUnfinishedPersonalisedSearchAndAsksForCriteria(String text) {
    var search = request(ConversationAction.RECOMMENDATION);
    search.setPersonalised(true);
    search.setExplicitSearch(true);
    search.setPositiveSemanticQuery(tastes("horror").getPositiveSemanticQuery());
    assertThat(process(search).getDecision())
        .isEqualTo(ConversationDecision.PREFERENCE_CLARIFICATION);
    org.mockito.Mockito.clearInvocations(vectors, retriever, ranker);
    var parser = new InputParser(new CommandParser(), mock(AiParser.class));
    var result = service.process("session", "user", parser.parse(text));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.SEARCH_CLARIFICATION);
    assertThat(result.getRecommendations()).isEmpty();
    assertThat(sessions.getSession("session", "user").isAwaitingPersonalisedPreferences())
        .isFalse();
    assertThat(sessions.getSession("session", "user").getPreferences()).isNull();
    verifyNoInteractions(vectors, retriever, ranker);
    var answer = request(ConversationAction.REFINE);
    answer.setPositiveSemanticQuery(tastes("adventure").getPositiveSemanticQuery());
    var resumed = process(answer);
    assertThat(resumed.isPreferenceClarificationRequired()).isFalse();
    assertThat(resumed.getRecommendations())
        .hasSize(text.equals("2 more") ? 2 : text.equals("another") ? 1 : 5);
    assertThat(sessions.getSession("session", "user").getPreferences()).isNull();
    assertThat(resumed.getPositiveSemanticQuery().getGenres()).containsExactly("adventure");
  }

  @Test
  void tasteReplyDoesNotPromotePendingSearchConstraintsOrSemanticsToSavedPreferences() {
    var search = request(ConversationAction.RECOMMENDATION);
    search.setPersonalised(true);
    search.setExplicitSearch(true);
    var filter = new ExactCriteria();
    filter.setAuthors(List.of("Suzanne Collins"));
    filter.setDuration("under 3 hours");
    search.setMustInclude(filter);
    search.setPositiveSemanticQuery(tastes("fantasy").getPositiveSemanticQuery());
    process(search);
    var reply = request(ConversationAction.RECOMMENDATION);
    reply.setPositiveSemanticQuery(tastes("horror").getPositiveSemanticQuery());
    var resumed = process(reply);
    assertThat(resumed.getMustInclude().getAuthors()).containsExactly("Suzanne Collins");
    assertThat(resumed.getMustInclude().getDuration()).isEqualTo("under 3 hours");
    var saved = sessions.getSession("session", "user").getPreferences();
    assertThat(saved.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(saved.getMustInclude().getAuthors()).isNullOrEmpty();
    assertThat(saved.getMustInclude().getDuration()).isNull();
    var next = request(ConversationAction.RECOMMENDATION);
    next.setExplicitSearch(true);
    next.setPositiveSemanticQuery(tastes("adventure").getPositiveSemanticQuery());
    var result = process(next);
    assertThat(result.getMustInclude().getAuthors()).isNullOrEmpty();
    assertThat(result.getMustInclude().getDuration()).isNull();
    assertThat(result.getPositiveSemanticQuery().getGenres()).doesNotContain("fantasy");
  }

  @Test
  void explicitPreferenceUpdateAbandonsTheUnfinishedSearch() {
    var search = request(ConversationAction.RECOMMENDATION);
    search.setPersonalised(true);
    var filter = new ExactCriteria();
    filter.setAuthors(List.of("Suzanne Collins"));
    search.setMustInclude(filter);
    process(search);
    process(request(ConversationAction.HELP));
    var result = process(update(tastes("horror")));
    assertThat(result.getRecommendations()).isEmpty();
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.PREFERENCES_UPDATED);
    assertThat(
            sessions
                .getSession("session", "user")
                .getPreferences()
                .getPositiveSemanticQuery()
                .getGenres())
        .containsExactly("horror");
    assertThat(sessions.getSession("session", "user").getPendingDecision()).isNull();
  }

  @Test
  void countAbandonsGenreQuestionAndHelpDoesNotRestoreIt() {
    var search = request(ConversationAction.RECOMMENDATION);
    search.setPersonalised(true);
    process(search);
    var count = request(ConversationAction.CHANGE_COUNT);
    count.setBookCount(2);
    process(count);
    process(request(ConversationAction.HELP));
    assertThat(sessions.getSession("session", "user").getPendingDecision()).isNull();
    var reply = request(ConversationAction.RECOMMENDATION);
    reply.setPositiveSemanticQuery(tastes("horror").getPositiveSemanticQuery());
    assertThat(process(reply).getRecommendations()).hasSize(2);
  }

  @Test
  void unrelatedPreferenceEditAbandonsThePendingGenreQuestion() {
    var search = request(ConversationAction.RECOMMENDATION);
    search.setPersonalised(true);
    process(search);
    var filterPreferences = new SessionPreferences();
    var filter = new ExactCriteria();
    filter.setLanguage("English");
    filterPreferences.setMustInclude(filter);
    var result = process(update(filterPreferences));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.PREFERENCES_UPDATED);
    assertThat(sessions.getSession("session", "user").getPendingDecision()).isNull();
    var answer = request(ConversationAction.RECOMMENDATION);
    answer.setPositiveSemanticQuery(tastes("horror").getPositiveSemanticQuery());
    assertThat(process(answer).getRecommendations()).hasSize(5);
    assertThat(
            sessions.getSession("session", "user").getPreferences().getMustInclude().getLanguage())
        .isEqualTo("English");
  }

  @Test
  void resetClearsPreferencesAndCountButPreservesDisplayedAndPendingBooks() {
    process(update(tastes("horror")));
    var count = request(ConversationAction.CHANGE_COUNT);
    count.setBookCount(2);
    process(count);
    var page = process(request(ConversationAction.RECOMMENDATION));
    var session = sessions.getSession("session", "user");
    var pending = List.copyOf(session.getActiveSearch().getPendingRecommendations());
    var shown = List.copyOf(session.getActiveSearch().getShownBookIds());
    org.mockito.Mockito.clearInvocations(users, vectors, retriever, ranker);
    var parser = new InputParser(new CommandParser(), mock(AiParser.class));
    var reset = service.process("session", "user", parser.parse("reset my preferences"));
    session = sessions.getSession("session", "user");
    assertThat(reset.getIntent()).isEqualTo(ConversationAction.RESET_PREFERENCES);
    assertThat(reset.getDecision()).isEqualTo(ConversationDecision.PREFERENCES_RESET);
    assertThat(session.getPreferences()).isNull();
    assertThat(session.getBookCount()).isEqualTo(5);
    assertThat(session.getActiveSearch().getPendingRecommendations())
        .usingRecursiveComparison()
        .isEqualTo(pending);
    assertThat(session.getActiveSearch().getShownBookIds()).containsExactlyElementsOf(shown);
    verifyNoInteractions(users, vectors, retriever, ranker);
    var response = new ConversationResponseGenerator(new AiResponseGenerator(null));
    assertThat(response.generate(reset.getRecommendations(), reset)).contains("reset", "5");
    var replay = process(request(ConversationAction.REPEAT));
    assertThat(replay.getDecision()).isEqualTo(ConversationDecision.PREFERENCES_RESET);
    assertThat(process(request(ConversationAction.REPEAT_RECOMMENDATIONS)).getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactlyElementsOf(Recommendation.bookIds(page.getRecommendations()));
    assertThat(process(request(ConversationAction.MORE_RESULTS)).getRecommendations()).hasSize(5);
    var personalised = request(ConversationAction.RECOMMENDATION);
    personalised.setPersonalised(true);
    assertThat(process(personalised).isPreferenceClarificationRequired()).isTrue();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void resetCancelsPendingPreferenceQuestions(boolean personalisation) {
    var pending =
        request(
            personalisation
                ? ConversationAction.RECOMMENDATION
                : ConversationAction.UPDATE_PREFERENCES);
    pending.setPersonalised(personalisation);
    process(pending);
    process(request(ConversationAction.RESET_PREFERENCES));
    var session = sessions.getSession("session", "user");
    assertThat(session.isAwaitingPreferenceUpdate()).isFalse();
    assertThat(session.isAwaitingPersonalisedPreferences()).isFalse();
    assertThat(session.getPreferences()).isNull();
  }

  private ConversationDraft request(ConversationAction intent) {
    var request = new ConversationDraft();
    request.setIntent(intent);
    return request;
  }

  private ConversationTurn process(ConversationDraft request) {
    return service.process("session", "user", request);
  }

  private SessionPreferences tastes(String... genres) {
    var result = new SessionPreferences();
    var query = new SemanticQuery();
    query.setGenres(List.of(genres));
    result.setPositiveSemanticQuery(query);
    return result;
  }

  private ConversationDraft update(SessionPreferences changes) {
    var request = request(ConversationAction.UPDATE_PREFERENCES);
    request.setPreferenceUpdates(changes);
    return request;
  }

  private SessionPreferences saved() {
    return sessions.getSession("session", "user").getPreferences();
  }

  @Test
  void updatesAddReplaceAndRemoveOnlySpecifiedValuesWithoutSearching() {
    process(update(tastes("romance")));
    var narrator = new SemanticQuery();
    narrator.setNarrators(List.of("Reader"));
    var moreTastes = tastes("fantasy");
    moreTastes.setNegativeSemanticQuery(narrator);
    process(update(moreTastes));
    assertThat(saved().getPositiveSemanticQuery().getGenres())
        .containsExactly("romance", "fantasy");
    var replace = update(tastes("horror"));
    replace.setReplacePreferenceFields(List.of("positiveSemanticQuery.genres"));
    process(replace);
    assertThat(saved().getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(saved().getNegativeSemanticQuery().getNarrators()).containsExactly("Reader");
    var remove = update(null);
    remove.setPreferenceRemovals(tastes("horror"));
    process(remove);
    assertThat(saved().getPositiveSemanticQuery().getGenres()).isNull();
    assertThat(saved().getNegativeSemanticQuery().getGenres()).isNull();
    var avoid = update(null);
    var dislikes = new SessionPreferences();
    dislikes.setNegativeSemanticQuery(tastes("romance").getPositiveSemanticQuery());
    avoid.setPreferenceUpdates(dislikes);
    avoid.setPreferenceRemovals(tastes("romance"));
    process(avoid);
    assertThat(saved().getNegativeSemanticQuery().getGenres()).containsExactly("romance");
    verifyNoInteractions(vectors, retriever, ranker, users);
  }

  @Test
  void asksForMissingDetailsAndShortReplyCompletesUpdateWithoutBooks() {
    var question = process(update(null));
    assertThat(question.getDecision())
        .isEqualTo(ConversationDecision.PREFERENCE_UPDATE_CLARIFICATION);
    assertThat(
            new ConversationResponseGenerator(new AiResponseGenerator(null))
                .generate(question.getRecommendations(), question))
        .contains("What would you like to change");
    assertThat(saved()).isNull();
    var reply = request(ConversationAction.RECOMMENDATION);
    reply.setPositiveSemanticQuery(tastes("horror").getPositiveSemanticQuery());
    var result = process(reply);
    assertThat(result.getIntent()).isEqualTo(ConversationAction.UPDATE_PREFERENCES);
    assertThat(result.getPositiveSemanticQuery()).isNull();
    assertThat(result.getRecommendations()).isEmpty();
    assertThat(saved().getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(process(request(ConversationAction.REPEAT)).getDecision())
        .isEqualTo(ConversationDecision.PREFERENCES_UPDATED);
    assertThat(process(request(ConversationAction.MORE_RESULTS)).getDecision())
        .isEqualTo(ConversationDecision.SEARCH_CLARIFICATION);
    verifyNoInteractions(vectors, retriever, ranker, users);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConversationAction.class,
      names = {"RECOMMENDATION", "REFINE"})
  void sparseParsedTasteReplyDerivesPreferenceUpdatesOnlyWhenContextRequiresThem(
      ConversationAction intent) {
    process(update(null));
    String raw = "fantasy but avoid romance, in English";
    var parsed =
        new org.springframework.ai.converter.BeanOutputConverter<>(
                com.gen3.recommenderagent.inputparser.UserIntent.class)
            .convert(
                "{\"intent\":\""
                    + intent
                    + "\","
                    + "\"positiveSemanticQuery\":{\"genres\":[\"fantasy\"]},"
                    + "\"negativeSemanticQuery\":{\"genres\":[\"romance\"]},"
                    + "\"mustInclude\":{\"language\":\"English\"}}");
    var client =
        mock(
            org.springframework.ai.chat.client.ChatClient.class,
            org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(org.mockito.ArgumentMatchers.anyString())
            .user(raw)
            .call()
            .responseEntity(
                org.mockito.ArgumentMatchers
                    .<org.springframework.ai.converter.StructuredOutputConverter<
                            com.gen3.recommenderagent.inputparser.UserIntent>>
                        any()))
        .thenReturn(new org.springframework.ai.chat.client.ResponseEntity<>(null, parsed));
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    var request = parser.parse(raw);
    assertThat(request.getPreferenceUpdates()).isNull();
    var result = service.process("session", "user", request);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.PREFERENCES_UPDATED);
    assertThat(saved().getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(saved().getNegativeSemanticQuery().getGenres()).containsExactly("romance");
    assertThat(saved().getMustInclude().getLanguage()).isEqualTo("English");
    assertThat(result.getRecommendations()).isEmpty();
    verifyNoInteractions(vectors, retriever, ranker, users);
  }

  @Test
  void explicitSearchAndCancelExitThePendingUpdate() {
    process(update(null));
    var search = request(ConversationAction.RECOMMENDATION);
    search.setExplicitSearch(true);
    search.setPositiveSemanticQuery(tastes("fantasy").getPositiveSemanticQuery());
    assertThat(process(search).getIntent()).isEqualTo(ConversationAction.RECOMMENDATION);
    assertThat(sessions.getSession("session", "user").isAwaitingPreferenceUpdate()).isFalse();
    process(update(null));
    process(request(ConversationAction.CANCEL));
    assertThat(sessions.getSession("session", "user").isAwaitingPreferenceUpdate()).isFalse();
  }

  @Test
  void cachedMoreRepeatAndRefillsKeepOldPreferencesButRefineAndNewSearchUseNewOnes()
      throws Exception {
    process(update(tastes("romance")));
    var search = request(ConversationAction.RECOMMENDATION);
    var include = new ExactCriteria();
    include.setDuration("under 5 hours");
    search.setMustInclude(include);
    assertThat(process(search).getRecommendations().getFirst().getBookId()).isEqualTo("book-1");
    var changed = update(tastes("horror"));
    changed.setReplacePreferenceFields(List.of("positiveSemanticQuery.genres"));
    process(changed);
    assertThat(
            process(request(ConversationAction.REPEAT_RECOMMENDATIONS))
                .getRecommendations()
                .getFirst()
                .getBookId())
        .isEqualTo("book-1");
    for (int i = 0; i < 4; i++) {
      var more = process(request(ConversationAction.MORE_RESULTS));
      assertThat(more.getPositiveSemanticQuery().getGenres()).containsExactly("romance");
      assertThat(more.getMustInclude().getDuration()).isEqualTo("under 5 hours");
      assertThat(more.getRecommendations().getFirst().getBookId()).isEqualTo("book-" + (6 + i * 5));
    }
    verify(users).findEmbeddingByUserId("user");
    var refine = request(ConversationAction.REFINE);
    var shorter = new ExactCriteria();
    shorter.setDuration("under 3 hours");
    refine.setMustInclude(shorter);
    var result = process(refine);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 3 hours");
    var fresh = process(request(ConversationAction.RECOMMENDATION));
    assertThat(fresh.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(fresh.getMustInclude().getDuration()).isNull();
    verify(users, times(3)).findEmbeddingByUserId("user");
  }

  @Test
  void shortUpdateReplyKeepsThePreviousPageAndQueue() {
    process(update(tastes("romance")));
    process(request(ConversationAction.RECOMMENDATION));
    process(update(null));
    var reply = request(ConversationAction.REFINE);
    reply.setPositiveSemanticQuery(tastes("horror").getPositiveSemanticQuery());
    assertThat(process(reply).getIntent()).isEqualTo(ConversationAction.UPDATE_PREFERENCES);
    assertThat(
            process(request(ConversationAction.REPEAT_RECOMMENDATIONS))
                .getRecommendations()
                .getFirst()
                .getBookId())
        .isEqualTo("book-1");
    var more = process(request(ConversationAction.MORE_RESULTS));
    assertThat(more.getRecommendations().getFirst().getBookId()).isEqualTo("book-6");
    assertThat(more.getPositiveSemanticQuery().getGenres()).containsExactly("romance");
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void updatedTastesDoNotEraseExplicitSearchCriteria() {
    process(update(tastes("romance")));
    var search = request(ConversationAction.RECOMMENDATION);
    search.setPositiveSemanticQuery(tastes("fantasy").getPositiveSemanticQuery());
    process(search);
    var change = update(tastes("horror"));
    change.setReplacePreferenceFields(List.of("positiveSemanticQuery.genres"));
    process(change);
    var refine = request(ConversationAction.REFINE);
    var shorter = new ExactCriteria();
    shorter.setDuration("under 3 hours");
    refine.setMustInclude(shorter);
    assertThat(process(refine).getPositiveSemanticQuery().getGenres())
        .containsExactly("horror", "fantasy");
  }

  @Test
  void newerSelectionQuestionsTakePriorityOverAnOlderPreferenceQuestion() {
    process(update(tastes("fantasy")));
    process(request(ConversationAction.RECOMMENDATION));
    process(update(null));
    var explain = request(ConversationAction.EXPLAIN_RECOMMENDATION);
    explain.setReferenceRank(6);
    assertThat(process(explain).getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    var selection = request(ConversationAction.UNKNOWN);
    selection.setRawText("1");
    assertThat(process(selection).getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(process(request(ConversationAction.CHANGE_COUNT)).getDecision())
        .isEqualTo(ConversationDecision.COUNT_UNCHANGED);
    var count = request(ConversationAction.UNKNOWN);
    count.setRawText("2");
    assertThat(process(count).getDecision()).isEqualTo(ConversationDecision.UNKNOWN);
    assertThat(sessions.getSession("session", "user").getPendingQuestion()).isNull();
    count.setIntent(ConversationAction.CHANGE_COUNT);
    count.setRawText("set count to 2");
    count.setBookCount(2);
    assertThat(process(count).getDecision()).isEqualTo(ConversationDecision.COUNT_CHANGED);
  }

  @Test
  void explicitMoreFiltersRemainCriteriaForTheNextRefinement() {
    process(update(tastes("romance")));
    process(request(ConversationAction.RECOMMENDATION));
    var more = request(ConversationAction.MORE_RESULTS);
    var english = new ExactCriteria();
    english.setLanguage("English");
    more.setMustInclude(english);
    process(more);
    var refine = request(ConversationAction.REFINE);
    var shorter = new ExactCriteria();
    shorter.setDuration("under 5 hours");
    refine.setMustInclude(shorter);
    var result = process(refine);
    assertThat(result.getMustInclude().getLanguage()).isEqualTo("English");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 5 hours");
  }

  @Test
  void refillsUseTheOriginalDatabaseTasteVectorAsWell() throws Exception {
    var old = new UserPreferenceEmbedding("user", new float[] {1, 0}, null);
    var changed = new UserPreferenceEmbedding("user", new float[] {0, 1}, null);
    when(users.findEmbeddingByUserId("user")).thenReturn(Optional.of(old), Optional.of(changed));
    process(request(ConversationAction.RECOMMENDATION));
    process(update(tastes("horror")));
    for (int i = 0; i < 4; i++) {
      process(request(ConversationAction.MORE_RESULTS));
    }
    var captured = ArgumentCaptor.forClass(RankingContext.class);
    verify(ranker, times(2)).rank(anyList(), eq(20), captured.capture());
    assertThat(captured.getAllValues())
        .allSatisfy(
            context ->
                assertThat(context.userPreferences().favouritesVector()).containsExactly(1, 0));
    verify(users).findEmbeddingByUserId("user");
    process(request(ConversationAction.RECOMMENDATION));
    verify(users, times(2)).findEmbeddingByUserId("user");
  }

  @Test
  void contradictoryUpdatesPreserveProfileAndBatch() throws Exception {
    process(update(tastes("romance")));
    process(request(ConversationAction.RECOMMENDATION));
    String before = new ObjectMapper().writeValueAsString(sessions.getSession("session", "user"));
    var impossible = new SessionPreferences();
    var include = new ExactCriteria();
    var exclude = new ExactCriteria();
    include.setLanguage("English");
    exclude.setLanguage("English");
    impossible.setMustInclude(include);
    impossible.setMustNotInclude(exclude);
    assertThatThrownBy(() -> process(update(impossible)))
        .isInstanceOf(InvalidRequestException.class);
    assertThat(new ObjectMapper().writeValueAsString(sessions.getSession("session", "user")))
        .isEqualTo(before);
  }
}
