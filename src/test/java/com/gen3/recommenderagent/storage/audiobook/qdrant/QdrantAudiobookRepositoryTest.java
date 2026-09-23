package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRepository.AudiobookEmbedding;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.DenseVector;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.VectorOutput;
import io.qdrant.client.grpc.Points.VectorsOutput;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/** Covers Qdrant repository behavior that does not require a running database. */
class QdrantAudiobookRepositoryTest {

  /** Verifies that Qdrant's dot-product score remains attached to the returned audiobook. */
  @Test
  void returnsDatabaseIndependentCandidateWithQdrantScore() throws Exception {
    QdrantClient client = mock(QdrantClient.class);
    QdrantPointMapper mapper = new QdrantPointMapper();
    AudiobookRecord book =
        new AudiobookRecord("book-1", "catalogue", "Title", List.of("Author"), "Summary");
    PointStruct point = mapper.toPoint(new AudiobookEmbedding(book, new float[] {0.6f, 0.8f}));
    ScoredPoint result =
        ScoredPoint.newBuilder()
            .setId(point.getId())
            .setScore(0.92f)
            .putAllPayload(point.getPayloadMap())
            .build();
    when(client.collectionExistsAsync("audiobooks"))
        .thenReturn(Futures.immediateFuture(true));
    when(client.searchAsync(any(SearchPoints.class)))
        .thenReturn(Futures.immediateFuture(List.of(result)));
    QdrantAudiobookRepository repository =
        new QdrantAudiobookRepository(
            client, mapper, mock(EmbeddingModel.class), "audiobooks", 2);

    List<AudiobookCandidate> candidates =
        repository.searchCandidates("fantasy", 5, new float[] {0.6f, 0.8f});

    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().audiobook()).isEqualTo(book);
    assertThat(candidates.getFirst().score()).isEqualTo(0.92, within(0.0001));
  }

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
