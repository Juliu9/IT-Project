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

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.AudiobookRecommendationWorkflow;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
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
import org.junit.jupiter.params.provider.EnumSource;

class RecommendationBatchFlowTest {
  private final CandidateRetriever retriever = mock(CandidateRetriever.class);
  private final Ranker ranker = mock(Ranker.class);
  private final SemanticQueryVectorService vectors = mock(SemanticQueryVectorService.class);
  private final UserPreferenceVectorRepository users = mock(UserPreferenceVectorRepository.class);
  private final RecommendationFlowTestSupport.InMemorySessionRepository sessions =
      new RecommendationFlowTestSupport.InMemorySessionRepository();
  private final SessionTransitionService service =
      ConversationTestSupport.transitions(
          sessions, new AudiobookRecommendationWorkflow(retriever, ranker, vectors, users));

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
    assertIds(process(request(Intent.RECOMMENDATION)), 1, 5);
    ConversationTurn shortPage = process(request(Intent.MORE_RESULTS));
    assertIds(shortPage, 6, 2);
    assertThat(shortPage.isNoMoreRecommendations()).isFalse();
    assertIds(process(request(Intent.REPEAT)), 6, 2);
    ConversationTurn exhausted = process(request(Intent.MORE_RESULTS));
    assertThat(exhausted.getRecommendations()).isEmpty();
    assertThat(exhausted.isNoMoreRecommendations()).isTrue();
    process(request(Intent.HELP));
    for (int i = 0; i < 2; i++) {
      ConversationTurn repeat = process(request(Intent.REPEAT));
      assertThat(repeat.getRecommendations()).isEmpty();
      assertThat(repeat.isNoMoreRecommendations()).isFalse();
      assertThat(repeat.getDecision())
          .isEqualTo(com.gen3.recommenderagent.domain.session.ConversationDecision.HELP);
    }
    verify(ranker, times(3)).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void moreRequiresAPreviousSearchEvenAfterOtherSessionActions() {
    assertThat(process(request(Intent.MORE_RESULTS)).isSearchClarificationRequired()).isTrue();
    process(request(Intent.HELP));
    ConversationDraft preferences = request(Intent.UPDATE_PREFERENCES);
    SemanticQuery tastes = new SemanticQuery();
    tastes.setGenres(List.of("romance"));
    var updates = new com.gen3.recommenderagent.domain.session.SessionPreferences();
    updates.setPositiveSemanticQuery(tastes);
    preferences.setPreferenceUpdates(updates);
    process(preferences);
    assertThat(process(request(Intent.MORE_RESULTS)).isSearchClarificationRequired()).isTrue();
    org.mockito.Mockito.verifyNoInteractions(ranker, retriever, vectors, users);
    process(request(Intent.RECOMMENDATION));
    process(request(Intent.CLEAR_HISTORY));
    assertThat(process(request(Intent.MORE_RESULTS)).isSearchClarificationRequired()).isTrue();
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void moreAndRepeatRestoreDetailedSearchContextWithoutHeavyWork() {
    ConversationDraft search = request(Intent.RECOMMENDATION);
    search.getPositiveSemanticQuery().setTopics(List.of("expeditions"));
    SemanticQuery avoid = new SemanticQuery();
    avoid.setKeywords(List.of("violence"));
    search.setNegativeSemanticQuery(avoid);
    ExactCriteria include = new ExactCriteria();
    include.setLanguage("English");
    search.setMustInclude(include);
    process(search);
    ConversationTurn more = process(request(Intent.MORE_RESULTS));
    ConversationTurn repeat = process(request(Intent.REPEAT));
    for (ConversationTurn result : List.of(more, repeat)) {
      assertThat(result.getPositiveSemanticQuery().getTopics()).containsExactly("expeditions");
      assertThat(result.getNegativeSemanticQuery().getKeywords()).containsExactly("violence");
      assertThat(result.getMustInclude().getLanguage()).isEqualTo("English");
      assertIds(result, 6, 5);
    }
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void anEmptyPreviousSearchStillAllowsMore() {
    when(ranker.rank(anyList(), anyInt(), any(RankingContext.class))).thenReturn(List.of());
    process(request(Intent.RECOMMENDATION));
    assertThat(process(request(Intent.MORE_RESULTS)).isSearchClarificationRequired()).isFalse();
    verify(ranker, times(2)).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void repeatReturnsTheLatestRequestWithoutConsumingOrInvalidatingTheBatch() {
    assertIds(process(request(Intent.RECOMMENDATION)), 1, 5);
    assertIds(process(request(Intent.REPEAT)), 1, 5);
    process(request(Intent.HELP));
    for (int i = 0; i < 2; i++) {
      ConversationTurn repeat = process(request(Intent.REPEAT));
      assertThat(repeat.getRecommendations()).isEmpty();
      assertThat(repeat.getDecision())
          .isEqualTo(com.gen3.recommenderagent.domain.session.ConversationDecision.HELP);
    }
    assertThat(sessions.getSession("session", "user").getShownBooks()).hasSize(5);
    assertIds(process(request(Intent.MORE_RESULTS)), 6, 5);
    assertIds(process(request(Intent.REPEAT)), 6, 5);
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
    verify(vectors).create(any());
  }

  @Test
  void repeatWithoutHistoryOrAfterClearingReturnsNoRecommendations() {
    assertThat(process(request(Intent.REPEAT)).getRecommendations()).isEmpty();
    process(request(Intent.RECOMMENDATION));
    process(request(Intent.CLEAR_HISTORY));
    assertThat(process(request(Intent.REPEAT)).getRecommendations()).isEmpty();
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void firstTwentyResultsArePagedAcrossPersistedSessionUpdatesWithoutRepeatingHeavyWork()
      throws Exception {
    for (int page = 0; page < 4; page++) {
      ConversationTurn result =
          process(request(page == 0 ? Intent.RECOMMENDATION : Intent.MORE_RESULTS));
      assertIds(result, page * 5 + 1, 5);
      assertThat(result.getRecommendations())
          .extracting(Recommendation::getRank)
          .containsExactly(1, 2, 3, 4, 5);
      assertThat(sessions.getSession("session", "user").getShownBooks()).hasSize((page + 1) * 5);
    }
    verify(users).findEmbeddingByUserId("user");
    verify(vectors).create(any());
    verify(retriever).getCandidates(any(), any(), eq(50), eq(Set.of()));
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
    assertIds(process(request(Intent.MORE_RESULTS)), 21, 5);
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
    assertIds(process(request(Intent.RECOMMENDATION)), 1, 5);
    ConversationDraft count = request(Intent.CHANGE_COUNT);
    count.setBookCount(3);
    process(count);
    assertIds(process(request(Intent.MORE_RESULTS)), 6, 3);
    assertIds(process(request(Intent.MORE_RESULTS)), 9, 3);
    assertIds(process(request(Intent.MORE_RESULTS)), 12, 3);
    assertIds(process(request(Intent.MORE_RESULTS)), 15, 3);
    count.setBookCount(5);
    process(count);
    assertIds(process(request(Intent.MORE_RESULTS)), 18, 5);
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
  @EnumSource(
      value = Intent.class,
      names = {"RECOMMENDATION", "REFINE", "CLEAR_HISTORY"})
  void changedSearchStateDiscardsThePreviousBatch(Intent intent) {
    process(request(Intent.RECOMMENDATION));
    process(request(intent));
    ConversationTurn next =
        process(
            request(intent == Intent.CLEAR_HISTORY ? Intent.RECOMMENDATION : Intent.MORE_RESULTS));
    // Searches consume a fresh first page immediately; preference/history actions do not.
    assertIds(next, intent == Intent.RECOMMENDATION || intent == Intent.REFINE ? 26 : 21, 5);
    verify(ranker, times(2)).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void moreWithEmptyParserCriteriaKeepsTheBatch() {
    process(request(Intent.RECOMMENDATION));
    ConversationDraft more = request(Intent.MORE_RESULTS);
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
    process(request(Intent.RECOMMENDATION));
    ConversationDraft more = request(Intent.MORE_RESULTS);
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
    process(request(Intent.RECOMMENDATION));
    assertThatThrownBy(() -> process(request(Intent.MORE_RESULTS)))
        .hasMessage("catalogue unavailable");
    assertThat(sessions.getSession("session", "user").getShownBooks()).hasSize(5);
    doReturn(batch(8, 20)).when(ranker).rank(anyList(), anyInt(), any(RankingContext.class));
    assertIds(process(request(Intent.MORE_RESULTS)), 6, 5);
  }

  @Test
  void helpKeepsTheBatchAndOversizedPageRequestsStillReturnAtMostFive() {
    process(request(Intent.RECOMMENDATION));
    process(request(Intent.HELP));
    ConversationDraft more = request(Intent.MORE_RESULTS);
    more.setBookCount(100);
    assertIds(process(more), 6, 5);
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @ParameterizedTest
  @EnumSource(
      value = Intent.class,
      names = {
        "MORE_RESULTS",
        "REPEAT",
        "REPEAT_RECOMMENDATIONS",
        "CHANGE_COUNT",
        "HELP",
        "UNKNOWN"
      })
  void unexpectedSemanticFieldsDoNotDestroyTheCachedSearch(Intent intent) {
    process(request(Intent.RECOMMENDATION));
    var control = request(intent);
    var query = new SemanticQuery();
    query.setGenres(List.of("horror"));
    control.setPositiveSemanticQuery(query);
    control.setNegativeSemanticQuery(query);
    control.setPersonalised(true);
    process(control);
    var more = process(request(Intent.MORE_RESULTS));
    assertIds(more, intent == Intent.MORE_RESULTS ? 11 : 6, 5);
    assertThat(more.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  private ConversationTurn process(ConversationDraft request) {
    return service.process("session", "user", request);
  }

  private ConversationDraft request(Intent intent) {
    ConversationDraft request = new ConversationDraft();
    request.setIntent(intent);
    if (intent == Intent.RECOMMENDATION) {
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
        .extracting(Recommendation::getBookId)
        .containsExactlyElementsOf(
            IntStream.range(start, start + size).mapToObj(i -> "book-" + i).toList());
  }
}
