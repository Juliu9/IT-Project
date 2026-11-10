package com.gen3.shared.readhistory.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.readhistoryjob.ReadHistoryApplication;
import com.gen3.readhistoryjob.ReadHistoryImporter;
import com.gen3.shared.csv.UserHistoryCsvReader;
import com.gen3.shared.readhistory.port.ReadHistoryWriter.ReadBook;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ReadHistoryPostgresIntegrationTest {
  @Container
  static GenericContainer<?> qdrant =
      new GenericContainer<>("qdrant/qdrant:v1.19.1").withExposedPorts(6334);

  @Container
  static org.testcontainers.solr.SolrContainer solr =
      com.gen3.recommenderagent.testsupport.SolrContainerTestSupport.newContainer();

  @Container
  static GenericContainer<?> postgres =
      new GenericContainer<>("postgres:17-alpine")
          .withEnv("POSTGRES_PASSWORD", "isolated-test-password")
          .withEnv("POSTGRES_DB", "history")
          .withExposedPorts(5432)
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

  @TempDir Path temporary;

  @Test
  void explicitSchemaAndAdditiveImportsPreserveHistoryAndAllowMissingCatalogueIds()
      throws Exception {
    try (var pool = pool()) {
      try (var connection = pool.getConnection();
          var statement = connection.createStatement()) {
        statement.execute("DROP TABLE IF EXISTS read_history");
      }
      var repository = new PostgresReadHistoryRepository(pool);
      assertThatThrownBy(() -> repository.findReadBookIds("missing", 10))
          .isInstanceOf(java.io.IOException.class);
      setup(pool);
      var path = temporary.resolve("history.csv");
      Files.writeString(
          path,
          """
          type,user,item,created_at,updated_at
          book,reader,rnib:book:123:Title,2026-01-01 00:00:00,NULL
          book,reader,rnib:book:123:Title,2026-01-01 00:00:00,2026-02-01 00:00:00
          book,reader,nls:book:not-in-catalogue,2026-01-01 00:00:00,NULL
          author,reader,ignored,ignored,ignored
          """);
      var importer = new ReadHistoryImporter(new UserHistoryCsvReader(), repository);
      assertThat(importer.run(path, 2)).isEqualTo(3);
      importer.run(path, 1);
      assertThat(repository.findReadBookIds("reader", 100))
          .containsExactlyInAnyOrder("rnib_123", "nls_not-in-catalogue");
      repository.addReadBooks(List.of(new ReadBook("other", "unrelated")));
      assertThatThrownBy(
              () ->
                  repository.addReadBooks(
                      List.of(
                          new ReadBook("atomic", "valid"),
                          new ReadBook(
                              "atomic",
                              java.util.stream.IntStream.range(0, 400)
                                  .mapToObj(i -> java.util.UUID.randomUUID().toString())
                                  .collect(java.util.stream.Collectors.joining())))))
          .isInstanceOf(java.io.IOException.class);
      assertThat(repository.findReadBookIds("atomic", 10)).isEmpty();
      assertThat(repository.findReadBookIds("missing", 100)).isEmpty();
      assertThat(repository.findReadBookIds("other", 100)).containsExactly("unrelated");
      assertThat(repository.findReadBookIds("reader", 1)).hasSize(1);
      Files.writeString(
          path,
          """
          type,user,item,created_at,updated_at
          book,reader,rnib:book:new,2026-01-01 00:00:00,NULL
          book,,rnib:book:invalid,2026-01-01 00:00:00,NULL
          """);
      assertThatThrownBy(() -> importer.run(path, 1)).hasMessageContaining("row 2");
      assertThat(repository.findReadBookIds("reader", 100)).contains("rnib_new", "rnib_123");
    }
  }

  @Test
  void benchmarkIndexedHistoryAndBothCatalogueExclusionsAtSupportedSizes() throws Exception {
    var encoder = new com.gen3.shared.audiobookcatalogue.qdrant.Bm25SparseTextEncoder();
    var mapper = new com.gen3.shared.audiobookcatalogue.qdrant.QdrantPointMapper(encoder);
    try (var pool = pool();
        var client =
            new io.qdrant.client.QdrantClient(
                io.qdrant.client.QdrantGrpcClient.newBuilder(
                        qdrant.getHost(), qdrant.getMappedPort(6334), false)
                    .build());
        var solrClient =
            com.gen3.recommenderagent.testsupport.SolrContainerTestSupport.newClient(solr)) {
      setup(pool);
      com.gen3.recommenderagent.testsupport.SolrContainerTestSupport.configureSchema(solrClient);
      var collection = com.gen3.recommenderagent.testsupport.SolrContainerTestSupport.COLLECTION;
      var qdrantBooks =
          new com.gen3.shared.audiobookcatalogue.qdrant.QdrantAudiobookRepository(
              client, mapper, encoder, "history-benchmark", 2);
      qdrantBooks.prepareMigration();
      var records =
          java.util.stream.Stream.of("book-1", "unread", "literal|id", "literal||id")
              .map(
                  id ->
                      new com.gen3.shared.audiobookcatalogue.model.AudiobookEmbedding(
                          new com.gen3.shared.audiobookcatalogue.model.AudiobookRecord(
                              id, "s", "Mystery adventure", List.of(), "Mystery adventure"),
                          new float[] {1, 0}))
              .toList();
      client.upsertAsync("history-benchmark", records.stream().map(mapper::toPoint).toList()).get();
      for (String id : List.of("book-1", "unread", "literal|id", "literal||id")) {
        var document = new org.apache.solr.common.SolrInputDocument();
        document.setField("id", id);
        document.setField("all", "mystery adventure");
        document.setField("title", "Mystery adventure");
        solrClient.add(collection, document);
      }
      solrClient.commit(collection);
      var solrBooks =
          new com.gen3.shared.audiobookcatalogue.solr.SolrAudiobookRepository(
              solrClient, collection);
      var history = new PostgresReadHistoryRepository(pool);
      for (int size : new int[] {100, 1000, 10000}) {
        String user = "benchmark-" + size;
        try (var connection = pool.getConnection();
            var insert =
                connection.prepareStatement(
                    "INSERT INTO read_history SELECT ?, 'book-' || i FROM generate_series(1, ?) i ON CONFLICT DO NOTHING")) {
          insert.setString(1, user);
          insert.setInt(2, size);
          insert.executeUpdate();
        }
        long start = System.nanoTime();
        var ids = history.findReadBookIds(user, 10001);
        long historyMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(ids).hasSize(size);
        var excluded = new java.util.HashSet<>(ids);
        excluded.addAll(List.of("literal|id", "literal||id"));
        var filters =
            new com.gen3.shared.audiobookcatalogue.model.AudiobookFilters(
                null, null, List.copyOf(excluded));
        start = System.nanoTime();
        assertUnread(qdrantBooks.searchByFilters(filters, 5));
        assertUnread(qdrantBooks.searchSemantic(new float[] {1, 0}, filters, 5));
        assertUnread(qdrantBooks.searchKeyword("mystery", filters, 5));
        assertUnread(qdrantBooks.searchHybrid(new float[] {1, 0}, "mystery", filters, 5));
        long qdrantMs = (System.nanoTime() - start) / 1_000_000;
        start = System.nanoTime();
        assertUnread(solrBooks.searchByFilters(filters, 5));
        assertUnread(solrBooks.searchKeyword("mystery", filters, 5));
        long solrMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf(
            "read.history.benchmark books=%d historyMs=%d qdrantFourQueriesMs=%d solrTwoQueriesMs=%d%n",
            size, historyMs, qdrantMs, solrMs);
      }
    }
  }

  private void assertUnread(
      List<com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate> books) {
    assertThat(books).extracting(book -> book.audiobook().id()).containsExactly("unread");
  }

  @Test
  void actualLauncherImportsWithoutAiRedisCatalogueOrSnapshots() throws Exception {
    try (var pool = pool()) {
      setup(pool);
    }
    var path = temporary.resolve("launcher.csv");
    Files.writeString(
        path,
        "type,user,item,created_at,updated_at\nbook,launcher,rnib:book:one,2026-01-01 00:00:00,NULL\n");
    try (var context =
        new SpringApplicationBuilder(ReadHistoryApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--read-history.import.enabled=true",
                "--read-history.import.csv-path=" + path,
                "--read-history.jdbc-url=" + url(),
                "--read-history.username=postgres",
                "--read-history.password=isolated-test-password",
                "--read-history.import.batch-size=1")) {
      assertThat(
              context.getBeanNamesForType(
                  com.gen3.shared.audiobookcatalogue.port.AudiobookCandidateSearch.class))
          .isEmpty();
      assertThat(
              context.getBeanNamesForType(
                  com.gen3.shared.audiobookcatalogue.snapshot.BookVectorSnapshot.class))
          .isEmpty();
    }
    try (var pool = pool()) {
      assertThat(new PostgresReadHistoryRepository(pool).findReadBookIds("launcher", 10))
          .containsExactly("rnib_one");
    }
  }

  private String url() {
    return "jdbc:postgresql://"
        + postgres.getHost()
        + ":"
        + postgres.getMappedPort(5432)
        + "/history";
  }

  private HikariDataSource pool() {
    var config = new HikariConfig();
    config.setJdbcUrl(url());
    config.setUsername("postgres");
    config.setPassword("isolated-test-password");
    return new HikariDataSource(config);
  }

  private void setup(HikariDataSource pool) throws Exception {
    try (var connection = pool.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(Files.readString(Path.of("postgres/read-history-schema.sql")));
    }
  }
}
