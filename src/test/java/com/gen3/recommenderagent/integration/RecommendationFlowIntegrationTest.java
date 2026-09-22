package com.gen3.recommenderagent.integration;

import static com.gen3.recommenderagent.integration.RecommendationFlowTestSupport.sessionPublisher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.api.RequestGateway;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Constraints;
import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationEngine;
import com.gen3.recommenderagent.engine.handlers.IntentHandlerFactory;
import com.gen3.recommenderagent.engine.handlers.NewRecommendationHandler;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.ranker.BaseSolrCandidateRetriever;
import com.gen3.recommenderagent.ranker.CandidateRetriever;
import com.gen3.recommenderagent.ranker.RankingService;
import com.gen3.recommenderagent.ranker.RecommendationQueryBuilder;
import com.gen3.recommenderagent.ranker.SolrAudiobookRepository;
import com.gen3.recommenderagent.ranker.strategy.HybridRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.PreferenceRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.RelevanceRankingStrategy;
import com.gen3.recommenderagent.response.AiResponseGenerator;
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
    CandidateRetriever candidateRetriever = new BaseSolrCandidateRetriever(repository);
    RankingService rankingService =
        new RankingService(
            new RelevanceRankingStrategy(),
            new PreferenceRankingStrategy(),
            new HybridRankingStrategy());
    NewRecommendationHandler newRecommendationHandler =
        new NewRecommendationHandler(
            candidateRetriever, rankingService, new RecommendationQueryBuilder());
    RecommendationFlowTestSupport.InMemorySessionRepository sessionRepository =
        new RecommendationFlowTestSupport.InMemorySessionRepository();

    RecommendationEngine engine =
        new RecommendationEngine(
            sessionRepository,
            sessionPublisher(sessionRepository),
            new IntentHandlerFactory(List.of(newRecommendationHandler)));

    RequestGateway gateway = new RequestGateway(inputParser, engine, new AiResponseGenerator(null));

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
    Query query = new Query();
    query.setGenres(List.of("science fiction"));

    Constraints constraints = new Constraints();
    constraints.setCount(3);

    SessionRequest request = new SessionRequest();
    request.setRawText(rawText);
    request.setIntent(Intent.NEW_RECOMMENDATION);
    request.setQuery(query);
    request.setConstraints(constraints);
    return request;
  }
}
