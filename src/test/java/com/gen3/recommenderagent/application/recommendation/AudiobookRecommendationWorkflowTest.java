package com.gen3.recommenderagent.application.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.conversation.intent.MoreResultsHandler;
import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.RefineHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.embedding.SemanticQueryVectorService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.retrieval.CandidateRetriever;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AudiobookRecommendationWorkflowTest {

  @Test
  void referenceVectorIsUsedExcludedAndReusedForMoreWithoutReadingStoredTastes() throws Exception {
    var search = mock(com.gen3.shared.audiobookcatalogue.port.AudiobookCandidateSearch.class);
    var model = mock(org.springframework.ai.embedding.EmbeddingModel.class);
    var queries = new com.gen3.recommenderagent.embedding.QueryEmbeddingService(model);
    var retriever =
        com.gen3.recommenderagent.testsupport.TestCandidateRetrievers.create(
            "test", search, queries);
    var ranker = mock(Ranker.class);
    when(ranker.rank(anyList(), eq(20), any(RankingContext.class))).thenReturn(List.of());
    var users = mock(UserPreferenceVectorRepository.class);
    var workflow =
        new AudiobookRecommendationWorkflow(
            retriever,
            ranker,
            new SemanticQueryVectorService(queries),
            users,
            com.gen3.recommenderagent.testsupport.TestHandlers.registry());
    var request = new ConversationDraft();
    request.setReadBookIds(Set.of("previously-read"));
    request.setIntent(RecommendationHandler.ACTION);
    request
        .getSearchContext()
        .setSimilarBook(
            new com.gen3.recommenderagent.domain.search.SimilarBookReference(
                "hunger", "The Hunger Games", List.of(), new float[] {1, 0}));
    var filter = new com.gen3.recommenderagent.domain.search.ExactCriteria();
    filter.setDuration("under 10 hours");
    request.setMustInclude(filter);
    var session = new Session();
    session.setUserId("user");
    workflow.recommend(request, session);
    request.setIntent(MoreResultsHandler.ACTION);
    workflow.recommend(request, session);
    var vector = ArgumentCaptor.forClass(float[].class);
    var filters =
        ArgumentCaptor.forClass(com.gen3.shared.audiobookcatalogue.model.AudiobookFilters.class);
    org.mockito.Mockito.verify(search, org.mockito.Mockito.times(2))
        .searchSemantic(vector.capture(), filters.capture(), eq(50));
    org.assertj.core.api.Assertions.assertThat(vector.getAllValues())
        .allSatisfy(v -> org.assertj.core.api.Assertions.assertThat(v).containsExactly(1f, 0f));
    org.assertj.core.api.Assertions.assertThat(filters.getValue().excludedBookIds())
        .contains("hunger", "previously-read");
    org.assertj.core.api.Assertions.assertThat(
            filters.getValue().mustInclude().maximumDurationMinutes())
        .isNotNull();
    org.mockito.Mockito.verifyNoInteractions(model, users);
  }

  @Test
  void refinementRebuildsFromOriginalReferenceAndCombinesPositiveNegativeEvidence() {
    var retriever = mock(CandidateRetriever.class);
    var ranker = mock(Ranker.class);
    when(retriever.getCandidates(any(), any(), eq(50), any())).thenReturn(List.of());
    when(ranker.rank(anyList(), eq(20), any(RankingContext.class))).thenReturn(List.of());
    var semantic = mock(SemanticQueryVectorService.class);
    var users = mock(UserPreferenceVectorRepository.class);
    var workflow =
        new AudiobookRecommendationWorkflow(
            retriever,
            ranker,
            semantic,
            users,
            com.gen3.recommenderagent.testsupport.TestHandlers.registry());
    var request = new ConversationDraft();
    request.setIntent(RefineHandler.ACTION);
    request
        .getSearchContext()
        .setSimilarBook(
            new com.gen3.recommenderagent.domain.search.SimilarBookReference(
                "hunger", "The Hunger Games", List.of(), new float[] {1, 0}));
    request.getSearchContext().setSimilarityQueryVector(new float[] {0, 1});
    when(semantic.create(request))
        .thenReturn(
            new SemanticQueryVectors(List.of(new float[] {0, 1}), List.of(new float[] {-1, 0})));
    workflow.recommend(request, new Session());
    float[] expected = com.gen3.shared.vector.VectorMath.normalize(new float[] {1.5f, .5f});
    org.assertj.core.api.Assertions.assertThat(
            request.getSearchContext().getSimilarityQueryVector())
        .containsExactly(expected);
    request.setIntent(MoreResultsHandler.ACTION);
    org.mockito.Mockito.clearInvocations(semantic);
    workflow.recommend(request, new Session());
    org.mockito.Mockito.verifyNoInteractions(semantic, users);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void personalisedTasteHasOneEqualShareWithOrWithoutAReference(boolean withReference)
      throws Exception {
    var retriever = mock(CandidateRetriever.class);
    when(retriever.getCandidates(any(), any(), eq(50), any())).thenReturn(List.of());
    var ranker = mock(Ranker.class);
    when(ranker.rank(anyList(), eq(20), any(RankingContext.class))).thenReturn(List.of());
    var semantic = mock(SemanticQueryVectorService.class);
    var users = mock(UserPreferenceVectorRepository.class);
    when(users.findEmbeddingByUserId("user"))
        .thenReturn(Optional.of(new UserPreferenceEmbedding("user", new float[] {0, 1}, null)));
    var request = new ConversationDraft();
    request.setPersonalised(true);
    request.setIntent(RecommendationHandler.ACTION);
    if (withReference) {
      request
          .getSearchContext()
          .setSimilarBook(
              new com.gen3.recommenderagent.domain.search.SimilarBookReference(
                  "hunger", "The Hunger Games", List.of(), new float[] {1, 0}));
    }
    when(semantic.create(request))
        .thenReturn(new SemanticQueryVectors(List.of(new float[] {1, 0}), List.of()));
    var session = new Session();
    session.setUserId("user");
    new AudiobookRecommendationWorkflow(
            retriever,
            ranker,
            semantic,
            users,
            com.gen3.recommenderagent.testsupport.TestHandlers.registry())
        .recommend(request, session);
    var vectors = ArgumentCaptor.forClass(SemanticQueryVectors.class);
    verify(retriever).getCandidates(eq(request), vectors.capture(), eq(50), any());
    float[] combined =
        com.gen3.shared.vector.VectorMath.directionalAverage(
            vectors.getValue().positive(), vectors.getValue().negative());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        withReference ? new float[] {.8944272f, .4472136f} : new float[] {.70710677f, .70710677f},
        combined,
        .00001f);
  }

  @Test
  void passesUserPreferencesAndShownBooksToRanker() throws Exception {
    CandidateRetriever retriever = mock(CandidateRetriever.class);
    Ranker ranker = mock(Ranker.class);
    SemanticQueryVectorService vectorService = mock(SemanticQueryVectorService.class);
    UserPreferenceVectorRepository userPreferences = mock(UserPreferenceVectorRepository.class);
    ConversationDraft request = new ConversationDraft();
    request.setBookCount(3);
    Session session = new Session();
    session.setUserId("user-1");
    session.getActiveSearch().addShownBookIds(List.of("already-shown"));
    SemanticQueryVectors vectors = SemanticQueryVectors.empty();
    UserPreferenceEmbedding preferences =
        new UserPreferenceEmbedding("user-1", new float[] {1, 0}, new float[0]);
    List<AudiobookCandidate> candidates = List.of();
    List<Recommendation> recommendations =
        List.of(new Recommendation("book-1", 1, 0.9, "Book One"));
    when(vectorService.create(request)).thenReturn(vectors);
    when(retriever.getCandidates(request, vectors, 50, Set.of("already-shown")))
        .thenReturn(candidates);
    when(userPreferences.findEmbeddingByUserId("user-1")).thenReturn(Optional.of(preferences));
    when(ranker.rank(anyList(), eq(20), any(RankingContext.class))).thenReturn(recommendations);
    AudiobookRecommendationWorkflow workflow =
        new AudiobookRecommendationWorkflow(
            retriever,
            ranker,
            vectorService,
            userPreferences,
            com.gen3.recommenderagent.testsupport.TestHandlers.registry());

    List<Recommendation> result = workflow.recommend(request, session);

    ArgumentCaptor<RankingContext> context = ArgumentCaptor.forClass(RankingContext.class);
    verify(ranker).rank(eq(candidates), eq(20), context.capture());
    assertSame(recommendations, result);
    assertSame(preferences, context.getValue().userPreferences());
    assertEquals(java.util.Set.of("already-shown"), context.getValue().excludedBookIds());
    verify(retriever).getCandidates(request, vectors, 50, Set.of("already-shown"));
  }
}
