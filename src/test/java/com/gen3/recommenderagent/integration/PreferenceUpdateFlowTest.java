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
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.AudiobookRecommendationWorkflow;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.MustNotInclude;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.embedding.SemanticQueryVectorService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
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
    when(vectors.createPersonalised(any())).thenReturn(encoded);
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

  private ConversationDraft request(Intent intent) {
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
    var request = request(Intent.UPDATE_PREFERENCES);
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
    var reply = request(Intent.RECOMMENDATION);
    reply.setPositiveSemanticQuery(tastes("horror").getPositiveSemanticQuery());
    var result = process(reply);
    assertThat(result.getIntent()).isEqualTo(Intent.UPDATE_PREFERENCES);
    assertThat(result.getPositiveSemanticQuery()).isNull();
    assertThat(result.getRecommendations()).isEmpty();
    assertThat(saved().getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(process(request(Intent.REPEAT)).getDecision())
        .isEqualTo(ConversationDecision.PREFERENCES_UPDATED);
    assertThat(process(request(Intent.MORE_RESULTS)).getDecision())
        .isEqualTo(ConversationDecision.SEARCH_CLARIFICATION);
    verifyNoInteractions(vectors, retriever, ranker, users);
  }

  @Test
  void explicitSearchAndCancelExitThePendingUpdate() {
    process(update(null));
    var search = request(Intent.RECOMMENDATION);
    search.setExplicitSearch(true);
    search.setPositiveSemanticQuery(tastes("fantasy").getPositiveSemanticQuery());
    assertThat(process(search).getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(sessions.getSession("session", "user").isAwaitingPreferenceUpdate()).isFalse();
    process(update(null));
    process(request(Intent.CANCEL));
    assertThat(sessions.getSession("session", "user").isAwaitingPreferenceUpdate()).isFalse();
  }

  @Test
  void cachedMoreRepeatAndRefillsKeepOldPreferencesButRefineAndNewSearchUseNewOnes()
      throws Exception {
    process(update(tastes("romance")));
    var search = request(Intent.RECOMMENDATION);
    var include = new MustInclude();
    include.setDuration("under 5 hours");
    search.setMustInclude(include);
    assertThat(process(search).getRecommendations().getFirst().getBookId()).isEqualTo("book-1");
    var changed = update(tastes("horror"));
    changed.setReplacePreferenceFields(List.of("positiveSemanticQuery.genres"));
    process(changed);
    assertThat(
            process(request(Intent.REPEAT_RECOMMENDATIONS))
                .getRecommendations()
                .getFirst()
                .getBookId())
        .isEqualTo("book-1");
    for (int i = 0; i < 4; i++) {
      var more = process(request(Intent.MORE_RESULTS));
      assertThat(more.getPositiveSemanticQuery().getGenres()).containsExactly("romance");
      assertThat(more.getMustInclude().getDuration()).isEqualTo("under 5 hours");
      assertThat(more.getRecommendations().getFirst().getBookId()).isEqualTo("book-" + (6 + i * 5));
    }
    verify(users).findEmbeddingByUserId("user");
    var refine = request(Intent.REFINE);
    var shorter = new MustInclude();
    shorter.setDuration("under 3 hours");
    refine.setMustInclude(shorter);
    var result = process(refine);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 3 hours");
    var fresh = process(request(Intent.RECOMMENDATION));
    assertThat(fresh.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(fresh.getMustInclude().getDuration()).isNull();
    verify(users, times(3)).findEmbeddingByUserId("user");
  }

  @Test
  void shortUpdateReplyKeepsThePreviousPageAndQueue() {
    process(update(tastes("romance")));
    process(request(Intent.RECOMMENDATION));
    process(update(null));
    var reply = request(Intent.REFINE);
    reply.setPositiveSemanticQuery(tastes("horror").getPositiveSemanticQuery());
    assertThat(process(reply).getIntent()).isEqualTo(Intent.UPDATE_PREFERENCES);
    assertThat(
            process(request(Intent.REPEAT_RECOMMENDATIONS))
                .getRecommendations()
                .getFirst()
                .getBookId())
        .isEqualTo("book-1");
    var more = process(request(Intent.MORE_RESULTS));
    assertThat(more.getRecommendations().getFirst().getBookId()).isEqualTo("book-6");
    assertThat(more.getPositiveSemanticQuery().getGenres()).containsExactly("romance");
    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  @Test
  void updatedTastesDoNotEraseExplicitSearchCriteria() {
    process(update(tastes("romance")));
    var search = request(Intent.RECOMMENDATION);
    search.setPositiveSemanticQuery(tastes("fantasy").getPositiveSemanticQuery());
    process(search);
    var change = update(tastes("horror"));
    change.setReplacePreferenceFields(List.of("positiveSemanticQuery.genres"));
    process(change);
    var refine = request(Intent.REFINE);
    var shorter = new MustInclude();
    shorter.setDuration("under 3 hours");
    refine.setMustInclude(shorter);
    assertThat(process(refine).getPositiveSemanticQuery().getGenres())
        .containsExactly("horror", "fantasy");
  }

  @Test
  void newerSelectionQuestionsTakePriorityOverAnOlderPreferenceQuestion() {
    process(update(tastes("fantasy")));
    process(request(Intent.RECOMMENDATION));
    process(update(null));
    var explain = request(Intent.EXPLAIN_RECOMMENDATION);
    explain.setReferenceRank(6);
    assertThat(process(explain).getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    var selection = request(Intent.UNKNOWN);
    selection.setRawText("1");
    assertThat(process(selection).getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    process(request(Intent.CHANGE_COUNT));
    var count = request(Intent.UNKNOWN);
    count.setRawText("2");
    assertThat(process(count).getDecision()).isEqualTo(ConversationDecision.COUNT_CHANGED);
  }

  @Test
  void explicitMoreFiltersRemainCriteriaForTheNextRefinement() {
    process(update(tastes("romance")));
    process(request(Intent.RECOMMENDATION));
    var more = request(Intent.MORE_RESULTS);
    var english = new MustInclude();
    english.setLanguage("English");
    more.setMustInclude(english);
    process(more);
    var refine = request(Intent.REFINE);
    var shorter = new MustInclude();
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
    process(request(Intent.RECOMMENDATION));
    process(update(tastes("horror")));
    for (int i = 0; i < 4; i++) {
      process(request(Intent.MORE_RESULTS));
    }
    var captured = ArgumentCaptor.forClass(RankingContext.class);
    verify(ranker, times(2)).rank(anyList(), eq(20), captured.capture());
    assertThat(captured.getAllValues())
        .allSatisfy(
            context ->
                assertThat(context.userPreferences().favouritesVector()).containsExactly(1, 0));
    verify(users).findEmbeddingByUserId("user");
    process(request(Intent.RECOMMENDATION));
    verify(users, times(2)).findEmbeddingByUserId("user");
  }

  @Test
  void contradictoryUpdatesPreserveProfileAndBatch() throws Exception {
    process(update(tastes("romance")));
    process(request(Intent.RECOMMENDATION));
    String before = new ObjectMapper().writeValueAsString(sessions.getSession("session", "user"));
    var impossible = new SessionPreferences();
    var include = new MustInclude();
    var exclude = new MustNotInclude();
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
