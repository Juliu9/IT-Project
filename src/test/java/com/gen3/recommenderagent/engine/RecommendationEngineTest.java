package com.gen3.recommenderagent.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Constraints;
import com.gen3.recommenderagent.domain.session.Preferences;
import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.events.DomainEventPublisher;
import com.gen3.recommenderagent.engine.events.SessionUpdatedEvent;
import com.gen3.recommenderagent.engine.handlers.IntentHandlerFactory;
import com.gen3.recommenderagent.engine.handlers.NewRecommendationHandler;
import com.gen3.recommenderagent.ranker.CandidateRetriever;
import com.gen3.recommenderagent.ranker.RankingService;
import com.gen3.recommenderagent.ranker.RecommendationQueryBuilder;
import com.gen3.recommenderagent.ranker.strategy.HybridRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.PreferenceRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.RelevanceRankingStrategy;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationEngineTest {

  @Mock private SessionRepository sessionCache;

  @Mock private DomainEventPublisher eventPublisher;

  @Mock private CandidateRetriever candidateRetriever;

  private RecommendationEngine recommendationEngine;

  @BeforeEach
  void setUp() {
    NewRecommendationHandler newRecommendationHandler =
        new NewRecommendationHandler(
            candidateRetriever,
            new RankingService(
                new RelevanceRankingStrategy(),
                new PreferenceRankingStrategy(),
                new HybridRankingStrategy()),
            new RecommendationQueryBuilder());

    recommendationEngine =
        new RecommendationEngine(
            sessionCache,
            eventPublisher,
            new IntentHandlerFactory(List.of(newRecommendationHandler)));
  }

  @Test
  void shouldRunNewRecommendationMainPath() {
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.NEW_RECOMMENDATION);

    Query query = new Query();
    query.setGenres(List.of("mystery"));
    query.setKeywords(List.of("light"));
    request.setQuery(query);

    Preferences preferences = new Preferences();
    preferences.setExclude(List.of("horror"));
    request.setPreferences(preferences);

    Constraints constraints = new Constraints();
    constraints.setCount(5);
    request.setConstraints(constraints);

    when(sessionCache.getSession("session-1")).thenReturn(null);
    when(candidateRetriever.getCandidates(anyString(), eq(50), same(request)))
        .thenReturn(
            List.of(
                document("book-1"),
                document("book-2"),
                document("book-3"),
                document("book-4"),
                document("book-5"),
                document("book-6")));

    List<Recommendation> result = recommendationEngine.process("session-1", "user-1", request);

    assertEquals(5, result.size());
    assertEquals("book-1", result.get(0).getBookId());
    assertEquals("book-5", result.get(4).getBookId());
    assertSame(result, request.getRecommendations());
    assertEquals(Intent.NEW_RECOMMENDATION, request.getIntent());

    ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
    verify(candidateRetriever).getCandidates(queryCaptor.capture(), eq(50), same(request));

    String solrQuery = queryCaptor.getValue();
    assertTrue(solrQuery.contains("mystery"));
    assertTrue(solrQuery.contains("light"));
    assertTrue(solrQuery.contains("-all:(\"horror\")"));

    verify(eventPublisher).publish(org.mockito.ArgumentMatchers.any(SessionUpdatedEvent.class));
  }

  @Test
  void shouldTreatMissingIntentAsUnknownInsteadOfThrowing() {
    SessionRequest request = new SessionRequest();
    when(sessionCache.getSession("session-2")).thenReturn(null);

    List<Recommendation> result = recommendationEngine.process("session-2", "user-2", request);

    assertEquals(Intent.UNKNOWN, request.getIntent());
    assertTrue(result.isEmpty());
  }

  private AudiobookCandidate document(String id) {
    return new AudiobookCandidate(
        new AudiobookRecord(id, "catalogue", "Book " + id, List.of(), null), null);
  }
}
