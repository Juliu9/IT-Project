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
import com.gen3.recommenderagent.application.ActionRegistry;
import com.gen3.recommenderagent.application.RequestApplicationService;
import com.gen3.recommenderagent.application.action.ChangeCountAction;
import com.gen3.recommenderagent.application.action.ClearHistoryAction;
import com.gen3.recommenderagent.application.action.MoreResultsAction;
import com.gen3.recommenderagent.application.action.NoOpAction;
import com.gen3.recommenderagent.application.action.RecommendationAction;
import com.gen3.recommenderagent.application.action.RefineAction;
import com.gen3.recommenderagent.application.action.UpdatePreferencesAction;
import com.gen3.recommenderagent.application.sessionservice.DefaultSessionService;
import com.gen3.recommenderagent.candidateretriever.CandidateRetrievalExecutor;
import com.gen3.recommenderagent.candidateretriever.CandidateRetriever;
import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectorService;
import com.gen3.recommenderagent.candidateretriever.SolrCandidateRetriever;
import com.gen3.recommenderagent.candidateretriever.retrievalplan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.AudiobookRecommendationWorkflow;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.ranker.RankingService;
import com.gen3.recommenderagent.ranker.strategy.HybridRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.RelevanceRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.SemanticQueryRankingStrategy;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import com.gen3.recommenderagent.testsupport.SolrContainerTestSupport;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
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

    InputParser inputParser = mock(InputParser.class);
    SessionRequest parsedRequest = parsedRequest(rawText);
    when(inputParser.parse(rawText))
        .thenReturn(new ResponseEntity<ChatResponse, SessionRequest>(null, parsedRequest));

    SolrAudiobookRepository repository =
        new SolrAudiobookRepository(solrClient, SolrContainerTestSupport.COLLECTION);
    com.gen3.recommenderagent.embedding.EmbeddingIndexer embeddingIndexer =
        mock(com.gen3.recommenderagent.embedding.EmbeddingIndexer.class);
    when(embeddingIndexer.embedSemanticQueries(anyList()))
        .thenReturn(List.of(new float[0], new float[0]));
    CandidateRetriever candidateRetriever =
        new SolrCandidateRetriever(
            repository,
            new CandidateRetrievalExecutor(embeddingIndexer, new AudiobookRetrievalPlanner()));
    RankingService rankingService =
        new RankingService(
            new RelevanceRankingStrategy(),
            new HybridRankingStrategy(new SemanticQueryRankingStrategy()));
    RecommendationAction recommendationAction =
        new RecommendationAction(
            new AudiobookRecommendationWorkflow(
                candidateRetriever,
                rankingService,
                new SemanticQueryVectorService(embeddingIndexer)));
    RecommendationFlowTestSupport.InMemorySessionRepository sessionRepository =
        new RecommendationFlowTestSupport.InMemorySessionRepository();

    ActionRegistry registry =
        new ActionRegistry(
            recommendationAction,
            new RefineAction(),
            new MoreResultsAction(),
            new ChangeCountAction(),
            new UpdatePreferencesAction(),
            new ClearHistoryAction(),
            new NoOpAction());
    RequestApplicationService applicationService =
        new RequestApplicationService(new DefaultSessionService(sessionRepository), registry);

    RequestGateway gateway =
        new RequestGateway(inputParser, applicationService, new AiResponseGenerator(null));

    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(gateway).build();

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

    Session savedSession = sessionRepository.getSession("session-1");
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

  private SessionRequest parsedRequest(String rawText) {
    SemanticQuery semanticQuery = new SemanticQuery();
    semanticQuery.setGenres(List.of("science fiction"));

    SessionRequest request = new SessionRequest();
    request.setRawText(rawText);
    request.setIntent(Intent.RECOMMENDATION);
    request.setPositiveSemanticQuery(semanticQuery);
    request.setBookCount(3);
    return request;
  }
}
