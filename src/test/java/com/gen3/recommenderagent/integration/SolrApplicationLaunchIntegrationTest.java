package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.embeddingjob.EmbeddingIndexApplication;
import com.gen3.recommenderagent.RecommenderAgentApplication;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import com.gen3.recommenderagent.storage.user.solr.SolrUserPreferenceRepository;
import com.gen3.recommenderagent.testsupport.SolrContainerTestSupport;
import com.gen3.shortdescriptionjob.ShortDescriptionApplication;
import com.gen3.shortdescriptionjob.provider.ShortDescriptionBatchProvider;
import com.gen3.userpreferencejob.UserPreferenceApplication;
import io.qdrant.client.QdrantClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;
import org.testcontainers.utility.DockerImageName;

/** Executes real launchers and runners using disposable stores and local AI doubles. */
@Testcontainers(disabledWithoutDocker = true)
class SolrApplicationLaunchIntegrationTest {
  private static final String BOOKS = SolrContainerTestSupport.COLLECTION;
  private static final String USERS = "launcher-users";
  @Container static final SolrContainer SOLR = SolrContainerTestSupport.newContainer();

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static SolrClient client;
  @TempDir Path directory;

  @BeforeAll
  static void prepareStores() throws Exception {
    client = SolrContainerTestSupport.newClient(SOLR);
    SolrContainerTestSupport.configureSchema(client);
    assertThat(SOLR.execInContainer("solr", "create", "-c", USERS).getExitCode()).isZero();
    for (String collection : List.of(BOOKS, USERS)) {
      var type = new FieldTypeDefinition();
      type.setAttributes(
          Map.of(
              "name",
              "launcher_vector",
              "class",
              "solr.DenseVectorField",
              "vectorDimension",
              2,
              "similarityFunction",
              "cosine"));
      new SchemaRequest.AddFieldType(type).process(client, collection);
      for (String field :
          collection.equals(BOOKS)
              ? List.of("embedding")
              : List.of("favouritesVector", "historyVector")) {
        new SchemaRequest.AddField(
                Map.of(
                    "name",
                    field,
                    "type",
                    "launcher_vector",
                    "stored",
                    true,
                    "indexed",
                    true,
                    "multiValued",
                    false))
            .process(client, collection);
      }
    }
    for (String id : List.of("local_100", "local_200")) {
      var book = new SolrInputDocument();
      book.setField("id", id);
      book.setField("title", "Launch test book");
      book.setField("description", "An adventurous story about friendship.");
      book.setField("all", "An adventurous story about friendship.");
      book.setField("client_custom_s", "keep");
      client.add(BOOKS, book);
    }
    client.commit(BOOKS);
  }

  @AfterAll
  static void closeClient() throws Exception {
    if (client != null) {
      client.close();
    }
  }

  @Test
  void runsSolrMaintenanceLaunchersThenServesAPersonalisedHttpRequest() throws Exception {
    try (var context =
        launch(
            EmbeddingIndexApplication.class,
            Embeddings.class,
            Map.of(
                "audiobook.embedding-index.enabled",
                "true",
                "audiobook.embedding-index.maximum-records",
                "1"))) {
      assertJobIsolation(context);
    }
    var books = new SolrAudiobookRepository(client, BOOKS, "embedding", 2);
    assertThat(books.findEmbeddingByBookId("local_100").orElseThrow()).containsExactly(0.6f, 0.8f);
    assertThat(books.findEmbeddingByBookId("local_200")).isEmpty();
    assertThat(client.getById(BOOKS, "local_100").getFieldValue("client_custom_s"))
        .isEqualTo("keep");

    Path output = directory.resolve("summaries");
    for (String mode : List.of("submit", "import", "submit", "recover")) {
      try (var context =
          launch(
              ShortDescriptionApplication.class,
              Summaries.class,
              Map.of(
                  "audiobook.short-description.batch.mode",
                  mode,
                  "audiobook.short-description.batch.provider",
                  "test",
                  "audiobook.short-description.batch.maximum-records",
                  "1",
                  "audiobook.short-description.batch.output-directory",
                  output.toString()))) {
        assertJobIsolation(context);
        assertThat(context.getBeansOfType(EmbeddingModel.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
      }
      assertThat(Files.exists(output.resolve("short-description-batch.json")))
          .isEqualTo(mode.equals("submit"));
    }
    assertThat(client.getById(BOOKS, "local_100").getFieldValue("short_description"))
        .isEqualTo("A friendship adventure.");
    assertThat(client.getById(BOOKS, "local_200").getFieldValue("short_description")).isNull();
    try (var files = Files.list(output)) {
      assertThat(
              files
                  .filter(
                      path ->
                          path.getFileName()
                              .toString()
                              .startsWith("short-description-batch-recovered-"))
                  .count())
          .isEqualTo(1);
    }

    var copied =
        new java.util.ArrayList<
            com.gen3.recommenderagent.storage.audiobook.model.AudiobookEmbedding>();
    var destination =
        mock(com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository.class);
    when(destination.importStoredBooks(anyList()))
        .thenAnswer(
            invocation -> {
              List<com.gen3.recommenderagent.storage.audiobook.model.AudiobookEmbedding> records =
                  invocation.getArgument(0);
              copied.addAll(records);
              return records.size();
            });
    new com.gen3.migrationjob.SolrToQdrantMigrator(books, destination, 1, 0)
        .run(new org.springframework.boot.DefaultApplicationArguments());
    assertThat(copied)
        .extracting(record -> record.record().id())
        .containsExactly("local_100", "local_200");
    assertThat(copied.getFirst().vector()).containsExactly(0.6f, 0.8f);
    assertThat(copied.getFirst().record().shortDescription()).isEqualTo("A friendship adventure.");
    assertThat(copied.getLast().vector()).isNull();

    Path favourites = directory.resolve("favourites.csv");
    Path history = directory.resolve("history.csv");
    Files.writeString(favourites, "type,user,item\nbook,launcher-user,local:book:100\n");
    Files.writeString(
        history,
        "type,user,item,created_at,updated_at\nbook,launcher-user,local:book:100,2026-01-01 00:00:00,NULL\n");
    try (var context =
        launch(
            UserPreferenceApplication.class,
            null,
            Map.of(
                "user.preferences.import-on-startup",
                "true",
                "user.preferences.favourites-csv-path",
                favourites.toString(),
                "user.preferences.history-csv-path",
                history.toString()))) {
      assertJobIsolation(context);
      assertThat(context.getBeansOfType(EmbeddingModel.class)).isEmpty();
      assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
    }
    var user =
        new SolrUserPreferenceRepository(client, USERS, 2, BOOKS)
            .findEmbeddingByUserId("launcher-user")
            .orElseThrow();
    assertThat(user.favouritesVector()).containsExactly(0.6f, 0.8f);
    assertThat(user.historyVector()).containsExactly(0.6f, 0.8f);

    try (var context =
        launch(
            RecommenderAgentApplication.class,
            ServerAi.class,
            Map.of(
                "server.port",
                "0",
                "spring.data.redis.host",
                REDIS.getHost(),
                "spring.data.redis.port",
                REDIS.getMappedPort(6379).toString(),
                "spring.data.redis.username",
                "",
                "spring.data.redis.password",
                ""))) {
      assertThat(context.getBeansOfType(QdrantClient.class)).isEmpty();
      assertThat(context.getBean(com.gen3.recommenderagent.storage.session.SessionRepository.class))
          .isInstanceOf(
              com.gen3.recommenderagent.storage.session.redis.RedisSessionRepository.class);
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      String response =
          RestClient.create()
              .post()
              .uri("http://localhost:" + port + "/api/v1/recommendations")
              .header("X-Session-Id", "launcher-session")
              .header("X-User-Id", "launcher-user")
              .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
              .body("Recommend an audiobook")
              .retrieve()
              .body(String.class);
      assertThat(response).contains("Launch test book", "local_100");
    }
  }

  private void assertJobIsolation(ConfigurableApplicationContext context) {
    assertThat(context.getBeansOfType(QdrantClient.class)).isEmpty();
    assertThat(context.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
    assertThat(context.getBeansOfType(com.gen3.recommenderagent.api.RequestGateway.class))
        .isEmpty();
  }

  private ConfigurableApplicationContext launch(
      Class<?> application, Class<?> doubles, Map<String, String> overrides) {
    var settings = new LinkedHashMap<String, String>();
    settings.put("spring.profiles.active", "solr-launcher-test");
    settings.put("audiobook.database", "solr");
    settings.put("solr.url", SolrContainerTestSupport.baseUrl(SOLR));
    settings.put("solr.collection", BOOKS);
    settings.put("solr.user-preference-collection", USERS);
    settings.put("solr.username", "");
    settings.put("solr.password", "");
    settings.put("embedding.dimensions", "2");
    settings.put("spring.ai.model.embedding", "none");
    settings.put("spring.ai.model.chat", "none");
    settings.put("spring.ai.openai.api-key", "local-test");
    settings.put("audiobook.embedding-index.enabled", "false");
    settings.put("audiobook.short-description.batch.mode", "disabled");
    settings.put("audiobook.qdrant.migration.enabled", "false");
    settings.put("user.preferences.import-on-startup", "false");
    settings.putAll(overrides);
    var builder =
        new SpringApplicationBuilder(application)
            .web(
                application == RecommenderAgentApplication.class
                    ? WebApplicationType.SERVLET
                    : WebApplicationType.NONE);
    if (doubles != null) {
      builder.sources(doubles);
    }
    return builder.run(
        settings.entrySet().stream()
            .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Embeddings {
    @Bean
    EmbeddingModel embeddingModel() {
      var model = mock(EmbeddingModel.class);
      when(model.embed(anyList()))
          .thenAnswer(
              invocation -> {
                List<String> texts = invocation.getArgument(0);
                return texts.stream().map(text -> new float[] {3, 4}).toList();
              });
      return model;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Summaries {
    @Bean
    ShortDescriptionBatchProvider summaryProvider() throws Exception {
      var provider = mock(ShortDescriptionBatchProvider.class);
      when(provider.id()).thenReturn("test");
      when(provider.upload(anyList(), any(Path.class))).thenReturn("input-local");
      when(provider.submit("input-local")).thenReturn("batch-local");
      when(provider.completedDescriptions("batch-local"))
          .thenReturn(Map.of("local_100", "A friendship adventure."));
      return provider;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ServerAi extends Embeddings {
    @Bean
    ChatModel chatModel() {
      var model = mock(ChatModel.class);
      when(model.getOptions())
          .thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
      when(model.call(any(Prompt.class)))
          .thenReturn(
              new ChatResponse(
                  List.of(
                      new Generation(
                          new AssistantMessage(
                              "{\"intent\":\"RECOMMENDATION\",\"bookCount\":1}")))))
          .thenThrow(new IllegalStateException("Use the built-in response"));
      return model;
    }
  }
}
