package com.gen3.recommenderagent.engine;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Constraints;
import com.gen3.recommenderagent.domain.session.Preferences;
import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.Recommendations;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.ranker.CandidateRetriever;
import com.gen3.recommenderagent.ranker.RankingService;
import com.gen3.recommenderagent.storage.sessioncache.SessionCache;
import com.gen3.recommenderagent.storage.userprofiledb.UserProfileDB;
import org.apache.solr.common.SolrDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationEngineTest {

    @Mock
    private SessionCache sessionCache;

    @Mock
    private UserProfileDB userProfileDB;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CandidateRetriever candidateRetriever;

    private RecommendationEngine recommendationEngine;

    @BeforeEach
    void setUp() {
        recommendationEngine = new RecommendationEngine(
                sessionCache,
                userProfileDB,
                eventPublisher,
                candidateRetriever,
                new RankingService()
        );
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
        when(candidateRetriever.getCandidates(anyString(), eq(50)))
                .thenReturn(List.of(
                        document("book-1"),
                        document("book-2"),
                        document("book-3"),
                        document("book-4"),
                        document("book-5"),
                        document("book-6")
                ));

        Recommendations result = recommendationEngine.process(
                "session-1",
                "user-1",
                request
        );

        assertEquals(5, result.getRecommendations().size());
        assertEquals("book-1", result.getRecommendations().get(0).getBookId());
        assertEquals("book-5", result.getRecommendations().get(4).getBookId());
        assertSame(result, request.getRecommendations());
        assertEquals(Intent.NEW_RECOMMENDATION, request.getIntent());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(candidateRetriever).getCandidates(queryCaptor.capture(), eq(50));

        String solrQuery = queryCaptor.getValue();
        assertTrue(solrQuery.contains("mystery"));
        assertTrue(solrQuery.contains("light"));
        assertTrue(solrQuery.contains("-all:(\"horror\")"));

        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(SessionUpdateEvent.class));
    }

    @Test
    void shouldTreatMissingIntentAsUnknownInsteadOfThrowing() {
        SessionRequest request = new SessionRequest();
        when(sessionCache.getSession("session-2")).thenReturn(null);

        Recommendations result = recommendationEngine.process(
                "session-2",
                "user-2",
                request
        );

        assertEquals(Intent.UNKNOWN, request.getIntent());
        assertTrue(result.getRecommendations().isEmpty());
    }

    private SolrDocument document(String id) {
        SolrDocument document = new SolrDocument();
        document.setField("id", id);
        return document;
    }
}
