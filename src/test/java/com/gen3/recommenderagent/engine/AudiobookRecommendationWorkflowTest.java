package com.gen3.recommenderagent.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.candidateretriever.CandidateRetriever;
import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectorService;
import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AudiobookRecommendationWorkflowTest {

  @Test
  void passesUserPreferencesAndShownBooksToRanker() throws Exception {
    CandidateRetriever retriever = mock(CandidateRetriever.class);
    Ranker ranker = mock(Ranker.class);
    SemanticQueryVectorService vectorService = mock(SemanticQueryVectorService.class);
    UserPreferenceVectorRepository userPreferences = mock(UserPreferenceVectorRepository.class);
    SessionRequest request = new SessionRequest();
    request.setBookCount(3);
    Session session = new Session();
    session.setUserId("user-1");
    session.addRecommendationIds(List.of("already-shown"));
    SemanticQueryVectors vectors = SemanticQueryVectors.empty();
    UserPreferenceEmbedding preferences =
        new UserPreferenceEmbedding("user-1", new float[] {1, 0}, new float[0]);
    List<AudiobookCandidate> candidates = List.of();
    List<Recommendation> recommendations =
        List.of(new Recommendation("book-1", 1, 0.9, "Book One"));
    when(vectorService.create(request)).thenReturn(vectors);
    when(retriever.getCandidates(request, vectors, 50)).thenReturn(candidates);
    when(userPreferences.findEmbeddingByUserId("user-1")).thenReturn(Optional.of(preferences));
    when(ranker.rank(anyList(), eq(3), any(RankingContext.class))).thenReturn(recommendations);
    AudiobookRecommendationWorkflow workflow =
        new AudiobookRecommendationWorkflow(retriever, ranker, vectorService, userPreferences);

    List<Recommendation> result = workflow.recommend(request, session);

    ArgumentCaptor<RankingContext> context = ArgumentCaptor.forClass(RankingContext.class);
    verify(ranker).rank(eq(candidates), eq(3), context.capture());
    assertSame(recommendations, result);
    assertSame(preferences, context.getValue().userPreferences());
    assertEquals(java.util.Set.of("already-shown"), context.getValue().excludedBookIds());
  }
}
