package com.gen3.catalogue.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.catalogue.model.AudiobookEmbedding;
import com.gen3.catalogue.model.AudiobookFilterConditions;
import com.gen3.catalogue.model.AudiobookFilters;
import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.vector.VectorMath;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class QdrantShownBookExclusionTest {
  @Container
  static GenericContainer<?> server =
      new GenericContainer<>("qdrant/qdrant:v1.19.1").withExposedPorts(6334);

  @Test
  void titleLookupFindsCaseInsensitiveMatchesWithoutChangingExistingPayloads() throws Exception {
    try (var client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(server.getHost(), server.getMappedPort(6334), false)
                .build())) {
      var encoder = new Bm25SparseTextEncoder();
      var repository =
          new QdrantAudiobookRepository(
              client, new QdrantPointMapper(encoder), encoder, "title-lookup", 2);
      repository.importStoredBooks(
          List.of(
              new AudiobookEmbedding(
                  new AudiobookRecord(
                      "hunger", "s", "The Hunger Games", List.of("Suzanne Collins"), "Description"),
                  new float[] {1, 0}),
              new AudiobookEmbedding(
                  new AudiobookRecord(
                      "guide",
                      "s",
                      "The Hunger Games Companion",
                      List.of("Other Author"),
                      "Description"),
                  new float[] {0, 1})));
      var results = repository.findBooksByTitle("the hunger games", null, 6);
      assertThat(results)
          .extracting(com.gen3.catalogue.model.AudiobookTitle::bookId)
          .containsExactlyInAnyOrder("hunger", "guide");
      assertThat(repository.findBooksByTitle("doesnotexist", null, 6)).isEmpty();
      assertThat(repository.findBooksByTitle("the hunger games", "suzanne collins", 6))
          .extracting(com.gen3.catalogue.model.AudiobookTitle::bookId)
          .containsExactly("hunger");
    }
  }

  @Test
  void emptyPreferenceReplacementRemovesBothOldNamedVectors() throws Exception {
    try (var client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(server.getHost(), server.getMappedPort(6334), false)
                .build())) {
      var users =
          new com.gen3.recommenderagent.storage.user.qdrant.QdrantUserPreferenceRepository(
              client,
              new com.gen3.recommenderagent.storage.user.qdrant.QdrantUserPreferenceMapper(),
              "clear-preferences",
              2);
      users.save(
          new com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding(
              "user", new float[] {1, 0}, new float[] {0, 1}));
      assertThat(users.findEmbeddingByUserId("user")).isPresent();
      users.clear("user");
      assertThat(users.findEmbeddingByUserId("user")).isEmpty();
      var id =
          new com.gen3.recommenderagent.storage.user.qdrant.QdrantUserPreferenceMapper()
              .pointId("user");
      var stored = client.retrieveAsync("clear-preferences", List.of(id), true, true, null).get();
      assertThat(stored).hasSize(1);
      assertThat(stored.getFirst().getVectors().getVectors().getVectorsMap()).isEmpty();
    }
  }

  @Test
  void catalogueOperationsUsePayloadBookIdsAndPreserveExistingPointIds() throws Exception {
    try (var client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(server.getHost(), server.getMappedPort(6334), false)
                .build())) {
      var encoder = new Bm25SparseTextEncoder();
      var mapper = new QdrantPointMapper(encoder);
      var repository = new QdrantAudiobookRepository(client, mapper, encoder, "opaque-ids", 2);
      repository.initialize();
      var book = new AudiobookRecord("catalogue-id", "s", "Original", List.of(), "Summary");
      var originalId = io.qdrant.client.PointIdFactory.id(98765L);
      client
          .upsertAsync(
              "opaque-ids",
              List.of(
                  mapper.toPoint(new AudiobookEmbedding(book, new float[] {1, 0})).toBuilder()
                      .setId(originalId)
                      .build()))
          .get();
      assertThat(repository.findEmbeddingByBookId("catalogue-id").orElseThrow())
          .containsExactly(1f, 0f);
      assertThat(
              repository.importStoredBooks(
                  List.of(new AudiobookEmbedding(book, new float[] {0, 1}))))
          .isZero();
      assertThat(repository.findEmbeddingByBookId("catalogue-id").orElseThrow())
          .containsExactly(1f, 0f);
      var points =
          client
              .scrollAsync(
                  io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                      .setCollectionName("opaque-ids")
                      .setLimit(10)
                      .build())
              .get()
              .getResultList();
      assertThat(points).hasSize(1);
      assertThat(points.getFirst().getId()).isEqualTo(originalId);

      var duplicateId = io.qdrant.client.PointIdFactory.id(java.util.UUID.randomUUID());
      client
          .upsertAsync(
              "opaque-ids",
              List.of(
                  mapper.toPoint(new AudiobookEmbedding(book, new float[] {1, 0})).toBuilder()
                      .setId(duplicateId)
                      .build()))
          .get();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> repository.findEmbeddingByBookId("catalogue-id"))
          .isInstanceOf(java.io.IOException.class)
          .hasMessageContaining("Duplicate bookId");
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> repository.importStoredBooks(List.of(new AudiobookEmbedding(book, null))))
          .isInstanceOf(java.io.IOException.class)
          .hasMessageContaining("Duplicate bookId");
    }
  }

  @Test
  void embeddingJobUpdatesVectorsAtOriginalPointIdsWithoutReplacingAnyPayload() throws Exception {
    try (QdrantClient client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(server.getHost(), server.getMappedPort(6334), false)
                .build())) {
      var encoder = new Bm25SparseTextEncoder();
      var mapper = new QdrantPointMapper(encoder);
      var repository = new QdrantAudiobookRepository(client, mapper, encoder, "embedding-job", 2);
      repository.initialize();
      var id = io.qdrant.client.PointIdFactory.id(java.util.UUID.randomUUID());
      var record =
          new AudiobookRecord(
              "job-book",
              "source",
              "Title",
              List.of("Author"),
              "Description",
              List.of(),
              "English",
              60,
              2020,
              "Keep this summary");
      var point =
          mapper.toStoredPoint(new AudiobookEmbedding(record, null)).toBuilder()
              .setId(id)
              .putPayload(
                  "client_custom_field", io.qdrant.client.ValueFactory.value("Keep this too"))
              .build();
      client.upsertAsync("embedding-job", List.of(point)).get();
      var model = org.mockito.Mockito.mock(org.springframework.ai.embedding.EmbeddingModel.class);
      org.mockito.Mockito.when(model.embed(org.mockito.ArgumentMatchers.anyList()))
          .thenReturn(List.of(new float[] {3, 4}));
      new com.gen3.embeddingjob.CatalogueEmbeddingJob(
              new com.gen3.embeddingjob.store.QdrantEmbeddingStore(
                  client, mapper, "embedding-job", 2),
              new com.gen3.embeddingjob.AudiobookEmbeddingIndexer(model),
              1,
              0)
          .run();
      var result =
          client.retrieveAsync("embedding-job", List.of(id), true, true, null).get().getFirst();
      assertThat(result.getPayloadMap()).isEqualTo(point.getPayloadMap());
      var dense = result.getVectors().getVectors().getVectorsMap().get("dense");
      assertThat(dense.hasDense()).isTrue();
      assertThat(dense.getDense().getDataList()).containsExactly(0.6f, 0.8f);
      assertThat(result.getVectors().getVectors().getVectorsMap()).containsKey("keywords");
      org.mockito.Mockito.verify(model).embed(org.mockito.ArgumentMatchers.anyList());
    }
  }

  @Test
  void migrationCopiesOnlyStoredDataAndPreservesDestinationRecordsOnReruns() throws Exception {
    try (QdrantClient client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(server.getHost(), server.getMappedPort(6334), false)
                .build())) {
      var encoder = org.mockito.Mockito.mock(SparseTextEncoder.class);
      var mapper = new QdrantPointMapper(encoder);
      var repository = new QdrantAudiobookRepository(client, mapper, encoder, "stored-books", 2);
      var source =
          new AudiobookRecord(
              "stored",
              "source",
              "Title",
              List.of("Author"),
              "Description",
              List.of(),
              "English",
              60,
              2020,
              "Existing summary");
      var missing = new AudiobookRecord("missing", "source", "No vector", List.of(), null);
      assertThat(
              repository.importStoredBooks(
                  List.of(
                      new AudiobookEmbedding(source, new float[] {2, 3}),
                      new AudiobookEmbedding(missing, null))))
          .isEqualTo(2);
      var points =
          client
              .retrieveAsync(
                  "stored-books",
                  List.of(mapper.pointId("stored"), mapper.pointId("missing")),
                  true,
                  true,
                  null)
              .get();
      var stored =
          points.stream()
              .filter(p -> p.getId().equals(mapper.pointId("stored")))
              .findFirst()
              .orElseThrow();
      var noVector =
          points.stream()
              .filter(p -> p.getId().equals(mapper.pointId("missing")))
              .findFirst()
              .orElseThrow();
      assertThat(stored.getPayloadMap().get("short_description").getStringValue())
          .isEqualTo("Existing summary");
      var dense = stored.getVectors().getVectors().getVectorsMap().get("dense");
      assertThat(dense.hasDense()).isTrue();
      assertThat(dense.getDense().getDataList()).containsExactly(2f, 3f);
      assertThat(stored.getVectors().getVectors().getVectorsMap()).doesNotContainKey("keywords");
      assertThat(noVector.getVectors().getVectors().getVectorsMap()).isEmpty();
      assertThat(
              repository.importStoredBooks(
                  List.of(
                      new AudiobookEmbedding(
                          new AudiobookRecord("stored", "source", "Replacement", List.of(), null),
                          null))))
          .isZero();
      var unchanged =
          client
              .retrieveAsync("stored-books", List.of(mapper.pointId("stored")), true, true, null)
              .get()
              .getFirst();
      assertThat(unchanged.getPayloadMap().get("short_description").getStringValue())
          .isEqualTo("Existing summary");
      org.mockito.Mockito.verifyNoInteractions(encoder);
    }
  }

  @Test
  void everySearchModeFindsTheUnseenBookBeyondTheFirstFifty() throws Exception {
    try (QdrantClient client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(server.getHost(), server.getMappedPort(6334), false)
                .build())) {
      var encoder = new Bm25SparseTextEncoder();
      var mapper = new QdrantPointMapper(encoder);
      var repository = new QdrantAudiobookRepository(client, mapper, encoder, "books", 2);
      repository.initialize();
      client
          .upsertAsync(
              "books",
              IntStream.range(0, 51)
                  .mapToObj(
                      index ->
                          new AudiobookEmbedding(
                              new AudiobookRecord(
                                  "book-" + index,
                                  "catalogue",
                                  "Fantasy book",
                                  List.of("Author"),
                                  "A fantasy adventure.",
                                  List.of("Narrator"),
                                  "English",
                                  100,
                                  2020,
                                  null),
                              VectorMath.normalize(new float[] {1, index / 100.0f})))
                  .map(mapper::toPoint)
                  .toList())
          .get();
      List<String> shown =
          repository.searchSemantic(new float[] {1, 0}, AudiobookFilters.empty(), 50).stream()
              .map(candidate -> candidate.audiobook().id())
              .toList();
      assertThat(shown).hasSize(50);
      var filters =
          new AudiobookFilters(
              new AudiobookFilterConditions(
                  List.of(), List.of(), "English", null, null, null, null, null),
              AudiobookFilterConditions.empty(),
              shown);
      var semantic = repository.searchSemantic(new float[] {1, 0}, filters, 50);
      assertThat(semantic).hasSize(1);
      String unseen = semantic.getFirst().audiobook().id();
      assertThat(shown).doesNotContain(unseen);
      assertThat(repository.searchKeyword("fantasy", filters, 50))
          .extracting(c -> c.audiobook().id())
          .containsExactly(unseen);
      assertThat(repository.searchHybrid(new float[] {1, 0}, "fantasy", filters, 50))
          .extracting(c -> c.audiobook().id())
          .containsExactly(unseen);
      assertThat(repository.searchByFilters(filters, 50))
          .extracting(c -> c.audiobook().id())
          .containsExactly(unseen);
    }
  }
}
