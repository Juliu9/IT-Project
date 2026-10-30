package com.gen3.snapshotjob;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.catalogue.model.AudiobookEmbedding;
import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.qdrant.Bm25SparseTextEncoder;
import com.gen3.catalogue.qdrant.QdrantAudiobookRepository;
import com.gen3.catalogue.qdrant.QdrantPointMapper;
import com.gen3.catalogue.snapshot.BookVectorSnapshot;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class QdrantSnapshotIntegrationTest {
  @Container
  static GenericContainer<?> server =
      new GenericContainer<>("qdrant/qdrant:v1.19.1").withExposedPorts(6334);

  @TempDir Path directory;

  @Test
  void exportsExistingVectorsAcrossPagesWithoutChangingAnyDatabaseFields() throws Exception {
    try (var client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(server.getHost(), server.getMappedPort(6334), false)
                .build())) {
      var encoder = new Bm25SparseTextEncoder();
      var mapper = new QdrantPointMapper(encoder);
      var repository = new QdrantAudiobookRepository(client, mapper, encoder, "snapshot-books", 2);
      repository.importStoredBooks(
          List.of(
              new AudiobookEmbedding(
                  new AudiobookRecord("one", "s", "Title", List.of(), "Description"),
                  new float[] {0.6f, 0.8f}),
              new AudiobookEmbedding(
                  new AudiobookRecord("missing", "s", "Other", List.of(), "Description"), null)));
      var ids = List.of(mapper.pointId("one"), mapper.pointId("missing"));
      var before = client.retrieveAsync("snapshot-books", ids, true, true, null).get();
      var snapshot =
          new BookVectorSnapshot(
              directory.resolve("snapshot.bin"), "qdrant", "snapshot-books", "model", 2);
      snapshot.rebuild(SnapshotSources.qdrant(client, "snapshot-books"), 1);
      assertThat(snapshot.findEmbeddingByBookId("one").orElseThrow()).containsExactly(0.6f, 0.8f);
      assertThat(snapshot.findEmbeddingByBookId("missing")).isEmpty();
      assertThat(client.retrieveAsync("snapshot-books", ids, true, true, null).get())
          .containsExactlyElementsOf(before);
    }
  }
}
