package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.conversation.intent.ChangeCountHandler;
import com.gen3.recommenderagent.application.conversation.intent.ClearHistoryHandler;
import com.gen3.recommenderagent.application.conversation.intent.HelpHandler;
import com.gen3.recommenderagent.application.conversation.intent.MoreResultsHandler;
import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.RefineHandler;
import com.gen3.recommenderagent.application.conversation.intent.RepeatHandler;
import com.gen3.recommenderagent.application.conversation.intent.UpdatePreferencesHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.recommendation.AudiobookRecommendationWorkflow;
import com.gen3.recommenderagent.application.session.SessionTransitionService;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.embedding.SemanticQueryVectorService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.retrieval.CandidateRetriever;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

class RecommendationBatchFlowTest {
  private final CandidateRetriever retriever = mock(CandidateRetriever.class);
  private final Ranker ranker = mock(Ranker.class);
  private final SemanticQueryVectorService vectors = mock(SemanticQueryVectorService.class);
  private final UserPreferenceVectorRepository users = mock(UserPreferenceVectorRepository.class);
  private final RecommendationFlowTestSupport.InMemorySessionRepository sessions =
      new RecommendationFlowTestSupport.InMemorySessionRepository();
  private final SessionTransitionService service =
      ConversationTestSupport.transitions(
          sessions,
          new AudiobookRecommendationWorkflow(
              retriever,
              ranker,
              vectors,
              users,
              com.gen3.recommenderagent.testsupport.TestHandlers.registry()));

  RecommendationBatchFlowTest() throws Exception {
    when(users.findEmbeddingByUserId("user")).thenReturn(Optional.empty());
    when(vectors.create(any())).thenReturn(SemanticQueryVectors.empty());
    when(retriever.getCandidates(any(), any(), eq(50), anySet())).thenReturn(List.of());
    when(ranker.rank(anyList(), anyInt(), any(RankingContext.class)))
        .thenReturn(batch(1, 20), batch(21, 20));
  }

  @Test
  void shortMorePagesAreAllowedAndRepeatKeepsTheLatestEmptyOutcome() {
    when(ranker.rank(anyList(), anyInt(), any(RankingContext.class)))
        .thenReturn(batch(1, 7), List.of());
    assertIds(process(request(RecommendationHandler.ACTION)), 1, 5);
    ConversationTurn shortPage = process(request(MoreResultsHandler.ACTION));
    assertIds(shortPage, 6, 2);
    assertThat(shortPage.isNoMoreRecommendations()).isFalse();
    assertIds(process(request(RepeatHandler.ACTION)), 6, 2);
    ConversationTurn exhausted = process(request(MoreResultsHandler.ACTION));
    assertThat(exhausted.getRecommendations()).isEmpty();
    assertThat(exhausted.isNoMoreRecommendations()).isTrue();
    process(request(HelpHandler.ACTION));
    for (int i = 0; i < 2; i++) {
      ConversationTurn repeat = process(request(RepeatHandler.ACTION));
      assertThat(repeat.getRecommendations()).isEmpty();
      assertThat(repeat.isNoMoreRecommendations()).isFalse();
      assertThat(repeat.getOutcome()).isNull();
    }
    verify(ranker, times(3)).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void moreRequiresAPreviousSearchEvenAfterOtherSessionActions() {
    assertThat(process(request(MoreResultsHandler.ACTION)).isSearchClarificationRequired())
        .isTrue();
    process(request(HelpHandler.ACTION));
    ConversationDraft preferences = request(UpdatePreferencesHandler.ACTION);
    SemanticQuery tastes = new SemanticQuery();
    tastes.setGenres(List.of("romance"));
    var updates = new SessionPreferences();
    updates.setPositiveSemanticQuery(tastes);
    preferences.setPreferenceUpdates(updates);
    process(preferences);
    assertThat(process(request(MoreResultsHandler.ACTION)).isSearchClarificationRequired())
        .isTrue();
    org.mockito.Mockito.verifyNoInteractions(ranker, retriever, vectors, users);
    process(request(RecommendationHandler.ACTION));
    process(request(ClearHistoryHandler.ACTION));
    assertThat(process(request(MoreResultsHandler.ACTION)).isSearchClarificationRequired())
        .isTrue();
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void moreAndRepeatRestoreDetailedSearchContextWithoutHeavyWork() {
    ConversationDraft search = request(RecommendationHandler.ACTION);
    search.getPositiveSemanticQuery().setTopics(List.of("expeditions"));
    SemanticQuery avoid = new SemanticQuery();
    avoid.setTopics(List.of("violence"));
    search.setNegativeSemanticQuery(avoid);
    ExactCriteria include = new ExactCriteria();
    include.setLanguage("English");
    search.setMustInclude(include);
    process(search);
    ConversationTurn more = process(request(MoreResultsHandler.ACTION));
    ConversationTurn repeat = process(request(RepeatHandler.ACTION));
    for (ConversationTurn result : List.of(more, repeat)) {
      assertThat(result.getPositiveSemanticQuery().getTopics()).containsExactly("expeditions");
      assertThat(result.getNegativeSemanticQuery().getTopics()).containsExactly("violence");
      assertThat(result.getMustInclude().getLanguage()).isEqualTo("English");
      assertIds(result, 6, 5);
    }
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void anEmptyPreviousSearchStillAllowsMore() {
    when(ranker.rank(anyList(), anyInt(), any(RankingContext.class))).thenReturn(List.of());
    process(request(RecommendationHandler.ACTION));
    assertThat(process(request(MoreResultsHandler.ACTION)).isSearchClarificationRequired())
        .isFalse();
    verify(ranker, times(2)).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void repeatReturnsTheLatestRequestWithoutConsumingOrInvalidatingTheBatch() {
    assertIds(process(request(RecommendationHandler.ACTION)), 1, 5);
    assertIds(process(request(RepeatHandler.ACTION)), 1, 5);
    process(request(HelpHandler.ACTION));
    for (int i = 0; i < 2; i++) {
      ConversationTurn repeat = process(request(RepeatHandler.ACTION));
      assertThat(repeat.getRecommendations()).isEmpty();
      assertThat(repeat.getOutcome()).isNull();
    }
    assertThat(sessions.getSession("session", "user").getActiveSearch().getShownBookIds())
        .hasSize(5);
    assertIds(process(request(MoreResultsHandler.ACTION)), 6, 5);
    assertIds(process(request(RepeatHandler.ACTION)), 6, 5);
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
    verify(vectors).create(any());
  }

  @Test
  void repeatWithoutHistoryOrAfterClearingReturnsNoRecommendations() {
    assertThat(process(request(RepeatHandler.ACTION)).getRecommendations()).isEmpty();
    process(request(RecommendationHandler.ACTION));
    process(request(ClearHistoryHandler.ACTION));
    assertThat(process(request(RepeatHandler.ACTION)).getRecommendations()).isEmpty();
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void firstTwentyResultsArePagedAcrossPersistedSessionUpdatesWithoutRepeatingHeavyWork()
      throws Exception {
    for (int page = 0; page < 4; page++) {
      ConversationTurn result =
          process(request(page == 0 ? RecommendationHandler.ACTION : MoreResultsHandler.ACTION));
      assertIds(result, page * 5 + 1, 5);
      assertThat(result.getRecommendations())
          .extracting(Recommendation::getRank)
          .containsExactly(1, 2, 3, 4, 5);
      assertThat(sessions.getSession("session", "user").getActiveSearch().getShownBookIds())
          .hasSize((page + 1) * 5);
    }
    verify(users).findEmbeddingByUserId("user");
    verify(vectors).create(any());
    verify(retriever).getCandidates(any(), any(), eq(50), eq(Set.of()));
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
    assertIds(process(request(MoreResultsHandler.ACTION)), 21, 5);
    verify(retriever)
        .getCandidates(
            any(),
            any(),
            eq(50),
            eq(
                IntStream.rangeClosed(1, 20)
                    .mapToObj(i -> "book-" + i)
                    .collect(java.util.stream.Collectors.toSet())));
  }

  @Test
  void changingCountKeepsTheBatchAndPartialPagesRefillWithoutRepeatingTheTail() {
    assertIds(process(request(RecommendationHandler.ACTION)), 1, 5);
    ConversationDraft count = request(ChangeCountHandler.ACTION);
    count.setBookCount(3);
    process(count);
    assertIds(process(request(MoreResultsHandler.ACTION)), 6, 3);
    assertIds(process(request(MoreResultsHandler.ACTION)), 9, 3);
    assertIds(process(request(MoreResultsHandler.ACTION)), 12, 3);
    assertIds(process(request(MoreResultsHandler.ACTION)), 15, 3);
    count.setBookCount(5);
    process(count);
    assertIds(process(request(MoreResultsHandler.ACTION)), 18, 5);
    verify(ranker, times(2)).rank(anyList(), eq(20), any(RankingContext.class));
    verify(retriever)
        .getCandidates(
            any(),
            any(),
            eq(50),
            eq(
                IntStream.rangeClosed(1, 20)
                    .mapToObj(i -> "book-" + i)
                    .collect(java.util.stream.Collectors.toSet())));
  }

  @ParameterizedTest
  @com.gen3.recommenderagent.testsupport.ActionSource(
      value = ConversationAction.class,
      names = {"RECOMMENDATION", "REFINE", "CLEAR_HISTORY"})
  void changedSearchStateDiscardsThePreviousBatch(ConversationAction intent) {
    process(request(RecommendationHandler.ACTION));
    process(request(intent));
    ConversationTurn next =
        process(
            request(
                intent == ClearHistoryHandler.ACTION
                    ? RecommendationHandler.ACTION
                    : MoreResultsHandler.ACTION));
    // Searches consume a fresh first page immediately; preference/history actions do not.
    assertIds(
        next,
        intent == RecommendationHandler.ACTION || intent == RefineHandler.ACTION ? 26 : 21,
        5);
    verify(ranker, times(2)).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void moreWithEmptyParserCriteriaKeepsTheBatch() {
    process(request(RecommendationHandler.ACTION));
    ConversationDraft more = request(MoreResultsHandler.ACTION);
    SemanticQuery empty = new SemanticQuery();
    empty.setGenres(java.util.Arrays.asList(null, "", "  "));
    more.setPositiveSemanticQuery(empty);
    more.setNegativeSemanticQuery(new SemanticQuery());
    ExactCriteria include = new ExactCriteria();
    include.setLanguage("  ");
    include.setAuthors(List.of());
    more.setMustInclude(include);
    ExactCriteria exclude = new ExactCriteria();
    exclude.setSource("");
    more.setMustNotInclude(exclude);
    assertIds(process(more), 6, 5);
    verify(vectors).create(any());
    verify(retriever).getCandidates(any(), any(), eq(50), anySet());
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void moreWithNewExactFiltersRetrievesAFreshBatch() {
    process(request(RecommendationHandler.ACTION));
    ConversationDraft more = request(MoreResultsHandler.ACTION);
    ExactCriteria include = new ExactCriteria();
    include.setLanguage("French");
    more.setMustInclude(include);
    assertIds(process(more), 21, 5);
    verify(ranker, times(2)).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void aFailedRefillDoesNotConsumeThePersistedTailOrShownIds() {
    when(ranker.rank(anyList(), anyInt(), any(RankingContext.class)))
        .thenReturn(batch(1, 7))
        .thenThrow(new IllegalStateException("catalogue unavailable"));
    process(request(RecommendationHandler.ACTION));
    assertThatThrownBy(() -> process(request(MoreResultsHandler.ACTION)))
        .hasMessage("catalogue unavailable");
    assertThat(sessions.getSession("session", "user").getActiveSearch().getShownBookIds())
        .hasSize(5);
    doReturn(batch(8, 20)).when(ranker).rank(anyList(), anyInt(), any(RankingContext.class));
    assertIds(process(request(MoreResultsHandler.ACTION)), 6, 5);
  }

  @Test
  void helpKeepsTheBatchAndOversizedPageRequestsStillReturnAtMostFive() {
    process(request(RecommendationHandler.ACTION));
    process(request(HelpHandler.ACTION));
    ConversationDraft more = request(MoreResultsHandler.ACTION);
    more.setBookCount(100);
    assertIds(process(more), 6, 5);
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @ParameterizedTest
  @com.gen3.recommenderagent.testsupport.ActionSource(
      value = ConversationAction.class,
      names = {
        "MORE_RESULTS",
        "REPEAT",
        "REPEAT_RECOMMENDATIONS",
        "CHANGE_COUNT",
        "HELP",
        "UNKNOWN"
      })
  void unexpectedSemanticFieldsDoNotDestroyTheCachedSearch(ConversationAction intent) {
    process(request(RecommendationHandler.ACTION));
    var control = request(intent);
    var query = new SemanticQuery();
    query.setGenres(List.of("horror"));
    control.setPositiveSemanticQuery(query);
    control.setNegativeSemanticQuery(query);
    control.setPersonalised(true);
    process(control);
    var more = process(request(MoreResultsHandler.ACTION));
    assertIds(more, intent == MoreResultsHandler.ACTION ? 11 : 6, 5);
    assertThat(more.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  private ConversationTurn process(ConversationDraft request) {
    return service.process("session", "user", request);
  }

  private ConversationDraft request(ConversationAction intent) {
    ConversationDraft request = new ConversationDraft();
    request.setIntent(intent);
    if (intent == RecommendationHandler.ACTION) {
      SemanticQuery query = new SemanticQuery();
      query.setGenres(List.of("fantasy"));
      request.setPositiveSemanticQuery(query);
    }
    return request;
  }

  private List<Recommendation> batch(int start, int size) {
    return IntStream.range(start, start + size)
        .mapToObj(
            i ->
                new Recommendation("book-" + i, i - start + 1, 0.9, "Title " + i, "Short summary."))
        .toList();
  }

  private void assertIds(ConversationTurn request, int start, int size) {
    assertThat(request.getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactlyElementsOf(
            IntStream.range(start, start + size).mapToObj(i -> "book-" + i).toList());
  }
}
