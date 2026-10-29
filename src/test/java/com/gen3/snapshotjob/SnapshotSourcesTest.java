package com.gen3.snapshotjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookEmbedding;
import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.solr.SolrAudiobookRepository;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SnapshotSourcesTest {
  @Test
  void solrUsesStoredVectorsAndContinuesPastFullPages() throws Exception {
    var repository = mock(SolrAudiobookRepository.class);
    when(repository.findStoredBooks(null, 1))
        .thenReturn(
            List.of(
                new AudiobookEmbedding(
                    new AudiobookRecord("book", "source", "Title", List.of(), "Description"),
                    new float[] {0.6f, 0.8f})));
    when(repository.findStoredBooks("book", 1)).thenReturn(List.of());
    var source = SnapshotSources.solr(repository);
    var first = source.page(null, 1);
    assertThat(first.entries().getFirst().vector()).containsExactly(0.6f, 0.8f);
    assertThat(first.nextCursor()).isEqualTo("book");
    assertThat(source.page(first.nextCursor(), 1).nextCursor()).isNull();
    verify(repository).findStoredBooks(null, 1);
    verify(repository).findStoredBooks("book", 1);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void qdrantReadsOnlyIdsAndDenseVectorsAndPreservesOpaquePagination() throws Exception {
    var client = mock(QdrantClient.class);
    var next = io.qdrant.client.PointIdFactory.id(123L);
    var point =
        io.qdrant.client.grpc.Points.RetrievedPoint.newBuilder()
            .putPayload("bookId", io.qdrant.client.ValueFactory.value("book"))
            .setVectors(
                io.qdrant.client.grpc.Points.VectorsOutput.newBuilder()
                    .setVectors(
                        io.qdrant.client.grpc.Points.NamedVectorsOutput.newBuilder()
                            .putVectors(
                                "dense",
                                io.qdrant.client.grpc.Points.VectorOutput.newBuilder()
                                    .setDense(
                                        io.qdrant.client.grpc.Points.DenseVector.newBuilder()
                                            .addData(0.6f)
                                            .addData(0.8f))
                                    .build())))
            .build();
    when(client.scrollAsync(any(ScrollPoints.class)))
        .thenReturn(
            Futures.immediateFuture(
                ScrollResponse.newBuilder().addResult(point).setNextPageOffset(next).build()),
            Futures.immediateFuture(ScrollResponse.getDefaultInstance()));
    var source = SnapshotSources.qdrant(client, "books");
    var first = source.page(null, 500);
    assertThat(first.entries().getFirst().bookId()).isEqualTo("book");
    assertThat(first.entries().getFirst().vector()).containsExactly(0.6f, 0.8f);
    assertThat(source.page(first.nextCursor(), 500).nextCursor()).isNull();
    var requests = ArgumentCaptor.forClass(ScrollPoints.class);
    verify(client, org.mockito.Mockito.times(2)).scrollAsync(requests.capture());
    assertThat(requests.getAllValues().getLast().getOffset()).isEqualTo(next);
    assertThat(requests.getAllValues().getFirst().getWithPayload())
        .isEqualTo(io.qdrant.client.WithPayloadSelectorFactory.include(List.of("bookId")));
    assertThat(requests.getAllValues().getFirst().getWithVectors())
        .isEqualTo(io.qdrant.client.WithVectorsSelectorFactory.include(List.of("dense")));
    verifyNoMoreInteractions(client);
  }
}
