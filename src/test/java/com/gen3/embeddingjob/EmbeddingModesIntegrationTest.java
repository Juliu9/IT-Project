package com.gen3.embeddingjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookEmbedding;
import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.qdrant.Bm25SparseTextEncoder;
import com.gen3.catalogue.qdrant.QdrantAudiobookRepository;
import com.gen3.catalogue.qdrant.QdrantPointMapper;
import com.gen3.catalogue.solr.SolrAudiobookRepository;
import com.gen3.embeddingjob.store.AudiobookEmbeddingStore;
import com.gen3.embeddingjob.store.QdrantEmbeddingStore;
import com.gen3.embeddingjob.store.SolrEmbeddingStore;
import com.gen3.recommenderagent.testsupport.SolrContainerTestSupport;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

@Testcontainers(disabledWithoutDocker = true)
class EmbeddingModesIntegrationTest {
  @Container static final SolrContainer SOLR = SolrContainerTestSupport.newContainer();

  @Container
  static final GenericContainer<?> QDRANT =
      new GenericContainer<>("qdrant/qdrant:v1.19.1").withExposedPorts(6334);

  private void run(
      AudiobookEmbeddingStore store, EmbeddingModel model, EmbeddingMode mode, int maximum)
      throws Exception {
    new CatalogueEmbeddingJob(store, new AudiobookEmbeddingIndexer(model), 1, maximum, mode).run();
  }

  @Test
  void solrFillsMissingVectorsOnceAndRebuildAllReplacesExistingVectors() throws Exception {
    try (var client = SolrContainerTestSupport.newClient(SOLR)) {
      String collection = SolrContainerTestSupport.COLLECTION;
      SolrContainerTestSupport.configureSchema(client);
      var type = new FieldTypeDefinition();
      type.setAttributes(
          Map.of(
              "name",
              "mode_vector",
              "class",
              "solr.DenseVectorField",
              "vectorDimension",
              2,
              "similarityFunction",
              "cosine"));
      new SchemaRequest.AddFieldType(type).process(client, collection);
      new SchemaRequest.AddField(
              Map.of(
                  "name", "taste_vector", "type", "mode_vector", "stored", true, "indexed", true))
          .process(client, collection);
      for (String id : List.of("a-existing", "b-missing", "c-missing")) {
        var book = new SolrInputDocument();
        book.setField("id", id);
        book.setField("title", id);
        book.setField("description", "A complete description");
        book.setField("short_description", "Keep summary");
        book.setField("client_custom_s", "Keep custom field");
        if (id.equals("a-existing")) {
          book.setField("taste_vector", List.of(1f, 0f));
        }
        client.add(collection, book);
      }
      client.commit(collection);
      var repository = new SolrAudiobookRepository(client, collection, "taste_vector", 2);
      var store = new SolrEmbeddingStore(repository);
      var model = mock(EmbeddingModel.class);
      when(model.embed(anyList())).thenReturn(List.of(new float[] {0, 1}));

      run(store, model, EmbeddingMode.MISSING_ONLY, 1);
      verify(model).embed(anyList());
      assertThat(repository.findEmbeddingByBookId("a-existing").orElseThrow())
          .containsExactly(1, 0);
      assertThat(repository.findEmbeddingByBookId("b-missing").orElseThrow()).containsExactly(0, 1);
      assertThat(repository.findEmbeddingByBookId("c-missing")).isEmpty();

      run(store, model, EmbeddingMode.MISSING_ONLY, 0);
      verify(model, times(2)).embed(anyList());
      clearInvocations(model);
      var beforeVersion = client.getById(collection, "a-existing").getFieldValue("_version_");
      run(store, model, EmbeddingMode.MISSING_ONLY, 0);
      verifyNoInteractions(model);
      assertThat(client.getById(collection, "a-existing").getFieldValue("_version_"))
          .isEqualTo(beforeVersion);

      when(model.embed(anyList())).thenReturn(List.of(new float[] {3, 4}));
      run(store, model, EmbeddingMode.REBUILD_ALL, 0);
      verify(model, times(3)).embed(anyList());
      for (String id : List.of("a-existing", "b-missing", "c-missing")) {
        assertThat(repository.findEmbeddingByBookId(id).orElseThrow()).containsExactly(0.6f, 0.8f);
        assertThat(client.getById(collection, id).getFieldValue("short_description"))
            .isEqualTo("Keep summary");
        assertThat(client.getById(collection, id).getFieldValue("client_custom_s"))
            .isEqualTo("Keep custom field");
      }
    }
  }

  @Test
  void qdrantTestsDensePresencePreservesOpaqueIdsAndSkipsExistingVectors() throws Exception {
    try (var client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(QDRANT.getHost(), QDRANT.getMappedPort(6334), false)
                .build())) {
      var encoder = new Bm25SparseTextEncoder();
      var mapper = new QdrantPointMapper(encoder);
      String collection = "embedding-modes";
      var repository = new QdrantAudiobookRepository(client, mapper, encoder, collection, 2);
      repository.initialize();
      var ids =
          List.of(
              io.qdrant.client.PointIdFactory.id(11L),
              io.qdrant.client.PointIdFactory.id(22L),
              io.qdrant.client.PointIdFactory.id(33L));
      for (int i = 0; i < ids.size(); i++) {
        var book =
            new AudiobookRecord(
                "book-" + i,
                "source",
                "Book " + i,
                List.of("Author"),
                "Full description",
                List.of(),
                "English",
                60,
                2020,
                "Keep summary");
        var point =
            mapper
                .toStoredPoint(new AudiobookEmbedding(book, i == 0 ? new float[] {1, 0} : null))
                .toBuilder()
                .setId(ids.get(i))
                .putPayload("custom", io.qdrant.client.ValueFactory.value("keep"));
        if (i == 2) {
          point.setVectors(
              io.qdrant.client.VectorsFactory.namedVectors(
                  Map.of(
                      "keywords", io.qdrant.client.VectorFactory.vector(List.of(1f), List.of(1)))));
        }
        client.upsertAsync(collection, List.of(point.build())).get();
      }
      var before = client.retrieveAsync(collection, ids, true, true, null).get();
      var store = new QdrantEmbeddingStore(client, mapper, collection, 2);
      var model = mock(EmbeddingModel.class);
      when(model.embed(anyList())).thenReturn(List.of(new float[] {0, 1}));
      run(store, model, EmbeddingMode.MISSING_ONLY, 1);
      verify(model).embed(anyList());
      assertThat(repository.findEmbeddingByBookId("book-0").orElseThrow()).containsExactly(1, 0);
      assertThat(repository.findEmbeddingByBookId("book-1").orElseThrow()).containsExactly(0, 1);
      assertThat(repository.findEmbeddingByBookId("book-2")).isEmpty();
      run(store, model, EmbeddingMode.MISSING_ONLY, 0);
      verify(model, times(2)).embed(anyList());
      clearInvocations(model);
      var complete = client.retrieveAsync(collection, ids, true, true, null).get();
      run(store, model, EmbeddingMode.MISSING_ONLY, 0);
      verifyNoInteractions(model);
      assertThat(client.retrieveAsync(collection, ids, true, true, null).get())
          .containsExactlyElementsOf(complete);

      when(model.embed(anyList())).thenReturn(List.of(new float[] {3, 4}));
      run(store, model, EmbeddingMode.REBUILD_ALL, 0);
      verify(model, times(3)).embed(anyList());
      var after = client.retrieveAsync(collection, ids, true, true, null).get();
      assertThat(after).extracting(point -> point.getId()).containsExactlyElementsOf(ids);
      for (int i = 0; i < ids.size(); i++) {
        assertThat(after.get(i).getPayloadMap()).isEqualTo(before.get(i).getPayloadMap());
        assertThat(repository.findEmbeddingByBookId("book-" + i).orElseThrow())
            .containsExactly(0.6f, 0.8f);
      }
    }
  }
}
