package com.gen3.recommenderagent.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.api.RequestGateway;
import com.gen3.recommenderagent.application.AudiobookRecommendationWorkflow;
import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.embedding.SemanticQueryVectorService;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.ranker.RankingService;
import com.gen3.recommenderagent.ranker.scorer.CompositeScorer;
import com.gen3.recommenderagent.ranker.scorer.RankingScoringProperties;
import com.gen3.recommenderagent.ranker.scorer.RetrievalRelevanceScorer;
import com.gen3.recommenderagent.ranker.scorer.UserPreferenceScorer;
import com.gen3.recommenderagent.ranker.scorer.WeightedScorer;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.retrieval.CandidateRetrievalService;
import com.gen3.recommenderagent.retrieval.CandidateRetriever;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import com.gen3.recommenderagent.testsupport.SolrContainerTestSupport;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

@Testcontainers(disabledWithoutDocker = true)
class RecommendationFlowIntegrationTest {

  @Container static final SolrContainer SOLR = SolrContainerTestSupport.newContainer();

  private static SolrClient solrClient;

  @BeforeAll
  static void setUpSolr() throws Exception {
    solrClient = SolrContainerTestSupport.newClient(SOLR);
    SolrContainerTestSupport.configureSchema(solrClient);
    SolrContainerTestSupport.seedBooks(solrClient);
  }

  @AfterAll
  static void closeSolrClient() throws Exception {
    if (solrClient != null) {
      solrClient.close();
    }
  }

  @Test
  void shouldRunRecommendationFlowAcrossTeamComponents() throws Exception {
    String rawText = "Recommend three science fiction audiobooks.";

    var sessionRepository = new RecommendationFlowTestSupport.InMemorySessionRepository();
    MockMvc mockMvc = recommendationEndpoint(rawText, parsedRequest(rawText), sessionRepository);

    String response =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("X-Session-Id", "session-1")
                    .header("X-User-Id", "user-1")
                    .contentType(MediaType.TEXT_PLAIN)
                    .content(rawText))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Session savedSession = sessionRepository.getSession("session-1", "user-1");
    assertNotNull(savedSession);
    assertEquals("user-1", savedSession.getUserId());
    assertEquals(1, savedSession.getRequests().size());
    var recommendations = savedSession.getRequests().getFirst().getRecommendations();
    assertEquals(3, recommendations.size());

    Set<String> returnedBookIds =
        recommendations.stream()
            .map(recommendation -> recommendation.getBookId())
            .collect(Collectors.toSet());

    Set<String> allowedScienceFictionIds =
        Set.of("book-101", "book-202", "book-303", "book-505", "book-606");
    assertTrue(allowedScienceFictionIds.containsAll(returnedBookIds));
    assertFalse(returnedBookIds.contains("book-404"));
    assertFalse(response.contains("book-404"));
    returnedBookIds.forEach(bookId -> assertTrue(response.contains(bookId)));
  }

  @Test
  void returnsTheNoMatchesFallbackWhenTheCatalogueHasNoEligibleBooks() throws Exception {
    String rawText = "Recommend science fiction from a missing source";
    var request = parsedRequest(rawText);
    var include = new com.gen3.recommenderagent.domain.session.MustInclude();
    include.setSource("missing-test-source");
    request.setMustInclude(include);
    var sessions = new RecommendationFlowTestSupport.InMemorySessionRepository();
    var endpoint = recommendationEndpoint(rawText, request, sessions);
    endpoint
        .perform(
            post("/api/v1/recommendations")
                .header("X-Session-Id", "no-matches")
                .header("X-User-Id", "user-1")
                .contentType(MediaType.TEXT_PLAIN)
                .content(rawText))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(
                    "I couldn't find a good match for "
                        + rawText
                        + " just yet. Try a genre, author, narrator, or a different description."));
    var saved = sessions.getSession("no-matches", "user-1");
    assertNotNull(saved);
    assertTrue(saved.getRequests().getFirst().getRecommendations().isEmpty());
    assertTrue(saved.getPendingRecommendations().isEmpty());
  }

  private MockMvc recommendationEndpoint(
      String rawText,
      ConversationDraft parsedRequest,
      RecommendationFlowTestSupport.InMemorySessionRepository sessionRepository) {
    InputParser inputParser = mock(InputParser.class);
    when(inputParser.parse(rawText)).thenReturn(parsedRequest);

    SolrAudiobookRepository repository =
        new SolrAudiobookRepository(solrClient, SolrContainerTestSupport.COLLECTION);
    com.gen3.recommenderagent.embedding.QueryEmbeddingService embeddingIndexer =
        mock(com.gen3.recommenderagent.embedding.QueryEmbeddingService.class);
    when(embeddingIndexer.embedSemanticQueries(anyList()))
        .thenReturn(List.of(new float[0], new float[0]));
    CandidateRetriever candidateRetriever =
        new CandidateRetrievalService(
            "solr", repository, embeddingIndexer, new AudiobookRetrievalPlanner());
    RankingService rankingService =
        new RankingService(
            new CompositeScorer(
                List.of(
                    new WeightedScorer(new RetrievalRelevanceScorer(), 1.0),
                    new WeightedScorer(new UserPreferenceScorer(scoringProperties()), 1.0))));
    com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository userPreferences =
        mock(com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository.class);
    AudiobookRecommendationWorkflow workflow =
        new AudiobookRecommendationWorkflow(
            candidateRetriever,
            rankingService,
            new SemanticQueryVectorService(embeddingIndexer),
            userPreferences);
    SessionTransitionService applicationService =
        new SessionTransitionService(sessionRepository, workflow);

    RequestGateway gateway =
        new RequestGateway(inputParser, applicationService, new AiResponseGenerator(null));

    return MockMvcBuilders.standaloneSetup(gateway).build();
  }

  private RankingScoringProperties scoringProperties() {
    return new RankingScoringProperties(
        1.0,
        1.0,
        0.5,
        new RankingScoringProperties.UserPreferenceWeights(0.6, 0.4),
        new RankingScoringProperties.UniversalRuleWeights(0.7, 0.3));
  }

  private ConversationDraft parsedRequest(String rawText) {
    SemanticQuery semanticQuery = new SemanticQuery();
    semanticQuery.setGenres(List.of("science fiction"));

    ConversationDraft request = new ConversationDraft();
    request.setRawText(rawText);
    request.setIntent(Intent.RECOMMENDATION);
    request.setPositiveSemanticQuery(semanticQuery);
    request.setBookCount(3);
    return request;
  }
}
