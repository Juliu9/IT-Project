package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.DenseVector;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.VectorOutput;
import io.qdrant.client.grpc.Points.VectorsOutput;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/** Covers Qdrant repository behavior that does not require a running database. */
class QdrantAudiobookRepositoryTest {

  /** Verifies a favourite book ID resolves to the unnamed dense vector stored in Qdrant. */
  @Test
  void findsEmbeddingByCatalogueBookId() throws Exception {
    QdrantClient client = mock(QdrantClient.class);
    QdrantPointMapper mapper = new QdrantPointMapper();
    RetrievedPoint result =
        RetrievedPoint.newBuilder()
            .setId(mapper.pointId("book-9"))
            .setVectors(
                VectorsOutput.newBuilder()
                    .setVector(
                        VectorOutput.newBuilder()
                            .setDense(
                                DenseVector.newBuilder().addData(0.6f).addData(0.8f))))
            .build();
    when(client.collectionExistsAsync("audiobooks"))
        .thenReturn(Futures.immediateFuture(true));
    when(client.retrieveAsync(
            eq("audiobooks"), anyList(), eq(false), eq(true), isNull()))
        .thenReturn(Futures.immediateFuture(List.of(result)));
    QdrantAudiobookRepository repository =
        new QdrantAudiobookRepository(
            client, mapper, mock(EmbeddingModel.class), "audiobooks", 2);

    Optional<float[]> vector = repository.findEmbeddingByBookId("book-9");

    assertThat(vector).isPresent();
    assertThat(vector.orElseThrow()).containsExactly(0.6f, 0.8f);
  }
}
