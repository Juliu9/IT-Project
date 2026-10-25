package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gen3.catalogue.model.AudiobookFilterConditions;
import com.gen3.catalogue.model.AudiobookFilters;
import com.gen3.catalogue.solr.SolrAudiobookRepository;
import com.gen3.recommenderagent.api.RequestGateway;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.AudiobookRecommendationWorkflow;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.embedding.QueryEmbeddingService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectorService;
import com.gen3.recommenderagent.ranker.RankingService;
import com.gen3.recommenderagent.ranker.scorer.CompositeScorer;
import com.gen3.recommenderagent.ranker.scorer.RankingScoringProperties;
import com.gen3.recommenderagent.ranker.scorer.UserPreferenceScorer;
import com.gen3.recommenderagent.ranker.scorer.WeightedScorer;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import com.gen3.recommenderagent.retrieval.CandidateRetrievalService;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.solr.SolrUserPreferenceRepository;
import com.gen3.recommenderagent.testsupport.SolrContainerTestSupport;
import com.gen3.userpreferencejob.UserPreferenceAggregatorService;
import com.gen3.userpreferencejob.model.UserFavourite;
import com.gen3.userpreferencejob.model.UserHistory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

/** Real Solr reads, writes, ranking and paging; no live AI or Qdrant services. */
@Testcontainers(disabledWithoutDocker = true)
class SolrBackendIntegrationTest {
  private static final String BOOKS = SolrContainerTestSupport.COLLECTION;
  private static final String USERS = "preferences-test";
  @Container static final SolrContainer SOLR = SolrContainerTestSupport.newContainer();
  private static SolrClient client;

  @BeforeAll
  static void prepareSolr() throws Exception {
    client = SolrContainerTestSupport.newClient(SOLR);
    SolrContainerTestSupport.configureSchema(client);
    SolrContainerTestSupport.seedBooks(client);
    assertThat(SOLR.execInContainer("solr", "create", "-c", USERS).getExitCode()).isZero();
    for (String collection : List.of(BOOKS, USERS)) {
      var type = new FieldTypeDefinition();
      type.setAttributes(
          Map.of(
              "name",
              "test_vector",
              "class",
              "solr.DenseVectorField",
              "vectorDimension",
              2,
              "similarityFunction",
              "cosine"));
      new SchemaRequest.AddFieldType(type).process(client, collection);
    }
    addField(BOOKS, "embedding", "test_vector");
    addField(USERS, "favouritesVector", "test_vector");
    addField(USERS, "historyVector", "test_vector");
    for (String id :
        List.of("book-101", "book-202", "book-303", "book-404", "book-505", "book-606")) {
      var document = new SolrInputDocument();
      document.setField("id", id);
      document.setField(
          "embedding",
          Map.of(
              "set",
              id.equals("book-404")
                  ? List.of(0f, 1f)
                  : id.equals("book-101") ? List.of(0.6f, 0.8f) : List.of(1f, 0f)));
      document.setField("publicationYear", Map.of("set", 2024));
      if (id.equals("book-606")) {
        document.setField("title", Map.of("set", "hiddenmatch"));
      }
      client.add(BOOKS, document);
    }
    client.commit(BOOKS);
  }

  private static void addField(String collection, String field, String type) throws Exception {
    new SchemaRequest.AddField(
            Map.of(
                "name", field, "type", type, "stored", true, "indexed", true, "multiValued", false))
        .process(client, collection);
  }

  @AfterAll
  static void closeClient() throws Exception {
    if (client != null) {
      client.close();
    }
  }

  private SolrAudiobookRepository books() {
    return new SolrAudiobookRepository(client, BOOKS, "embedding", 2);
  }

  private SolrUserPreferenceRepository users() {
    return new SolrUserPreferenceRepository(client, USERS, 2, BOOKS);
  }

  @Test
  void importAggregatesBookVectorsThenSupportsPersonalisedApiPagingAndExplanation()
      throws Exception {
    String user = "reader:+ /comma,quote\"";
    new UserPreferenceAggregatorService(books(), users())
        .aggregateAndSave(
            List.of(new UserFavourite(user, "book-404")),
            List.of(new UserHistory(user, "book-101", Instant.now())));
    var preferences = users().findEmbeddingByUserId(user).orElseThrow();
    assertThat(preferences.favouritesVector()).containsExactly(0, 1);
    assertThat(preferences.historyVector()).containsExactly(0.6f, 0.8f);
    var missing = books().findEmbeddingsByBookIds(List.of("missing", "book-404", "book-404"));
    assertThat(missing).containsOnlyKeys("book-404");

    var model = mock(org.springframework.ai.embedding.EmbeddingModel.class);
    var embeddings = new QueryEmbeddingService(model);
    var scoring =
        new RankingScoringProperties(
            1.0,
            1.0,
            0.5,
            new RankingScoringProperties.UserPreferenceWeights(0.6, 0.4),
            new RankingScoringProperties.UniversalRuleWeights(0.7, 0.3));
    var ranking =
        new RankingService(
            new CompositeScorer(
                List.of(new WeightedScorer(new UserPreferenceScorer(scoring), 1.0))));
    var workflow =
        new AudiobookRecommendationWorkflow(
            new CandidateRetrievalService(
                "solr", books(), embeddings, new AudiobookRetrievalPlanner()),
            ranking,
            new SemanticQueryVectorService(embeddings),
            users());
    var sessions = new RecommendationFlowTestSupport.InMemorySessionRepository();
    var service = ConversationTestSupport.transitions(sessions, workflow);
    var responses = new ConversationResponseGenerator(new AiResponseGenerator(null));
    var parser = org.mockito.Mockito.mock(com.gen3.recommenderagent.inputparser.InputParser.class);
    org.mockito.Mockito.when(parser.parse(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation -> {
              String raw = invocation.getArgument(0);
              var request = new ConversationDraft();
              request.setRawText(raw);
              request.setBookCount(2);
              request.setIntent(raw.equals("2 more") ? Intent.MORE_RESULTS : Intent.RECOMMENDATION);
              return request;
            });
    var gateway = new RequestGateway(parser, service, responses);
    var mvc = MockMvcBuilders.standaloneSetup(gateway).build();
    for (String raw : List.of("Recommend an audiobook", "2 more")) {
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/v1/recommendations")
                  .header("X-Session-Id", "session")
                  .header("X-User-Id", user)
                  .contentType("text/plain")
                  .content(raw))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
      assertThat(sessions.getSession("session", user).getLastRequest().getRecommendations())
          .hasSize(2);
      if (!raw.equals("2 more")) {
        assertThat(
                sessions
                    .getSession("session", user)
                    .getLastRequest()
                    .getRecommendations()
                    .getFirst()
                    .getBookId())
            .isEqualTo("book-404");
      }
    }
    assertThat(sessions.getSession("session", user).getShownBooks()).hasSize(4);
    var explain = new ConversationDraft();
    explain.setIntent(Intent.EXPLAIN_RECOMMENDATION);
    var explanation = service.process("session", user, explain);
    assertThat(explanation.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(explanation.getRecommendations()).hasSize(2);
    var fresh = new ConversationDraft();
    fresh.setIntent(Intent.RECOMMENDATION);
    assertThat(service.process("new-session", "new-user", fresh).getDecision())
        .isEqualTo(ConversationDecision.PREFERENCE_CLARIFICATION);
    verifyNoInteractions(model);
  }

  @Test
  void hybridSearchIncludesVectorsAndHonoursYearAndShownBookFilters() throws Exception {
    var include =
        new AudiobookFilterConditions(
            List.of(), List.of(), null, "integration-test", null, null, 2020, 2026);
    var filters =
        new AudiobookFilters(
            include, AudiobookFilterConditions.empty(), List.of("book-101", "book-404"));
    var candidates = books().searchHybrid(new float[] {1, 0}, "science fiction", filters, 50);
    assertThat(candidates).hasSize(4);
    assertThat(books().searchByFilters(filters, 50)).hasSize(4);
    assertThat(candidates)
        .allSatisfy(
            candidate -> {
              assertThat(candidate.embedding()).containsExactly(1, 0);
              assertThat(candidate.audiobook().id()).isNotIn("book-101", "book-404");
            });
  }

  @Test
  void keywordSearchUsesOnlyAllEvenWithAnExplicitFieldSelector() throws Exception {
    assertThat(books().searchKeyword("hiddenmatch", AudiobookFilters.empty(), 50)).isEmpty();
    assertThat(books().searchKeyword("title:hiddenmatch", AudiobookFilters.empty(), 50)).isEmpty();
    assertThat(books().searchKeyword("science fiction", AudiobookFilters.empty(), 50)).hasSize(5);
  }

  @Test
  void preferenceReplacementClearsOldVectorsAndPreservesOtherUserMetadata() throws Exception {
    users()
        .save(new UserPreferenceEmbedding("replacement", new float[] {1, 0}, new float[] {0, 1}));
    var metadata = new SolrInputDocument();
    metadata.setField("id", "replacement");
    metadata.setField("client_custom_s", Map.of("set", "keep"));
    client.add(USERS, metadata);
    client.commit(USERS);
    users().save(new UserPreferenceEmbedding("replacement", null, new float[] {1, 0}));
    var updated = users().findEmbeddingByUserId("replacement").orElseThrow();
    assertThat(updated.favouritesVector()).isNull();
    assertThat(updated.historyVector()).containsExactly(1, 0);
    assertThat(client.getById(USERS, "replacement").getFieldValue("client_custom_s"))
        .isEqualTo("keep");
    users().clear("replacement");
    assertThat(users().findEmbeddingByUserId("replacement")).isEmpty();
    var cleared = client.getById(USERS, "replacement");
    assertThat(cleared.getFieldValue("favouritesVector")).isNull();
    assertThat(cleared.getFieldValue("historyVector")).isNull();
    assertThat(cleared.getFieldValue("client_custom_s")).isEqualTo("keep");
  }
}
