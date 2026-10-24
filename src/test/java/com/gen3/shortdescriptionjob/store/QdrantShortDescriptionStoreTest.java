package com.gen3.shortdescriptionjob.store;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.qdrant.Bm25SparseTextEncoder;
import com.gen3.catalogue.qdrant.QdrantPointMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.CreateFieldIndexCollection;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.UpdateResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QdrantShortDescriptionStoreTest {
  @Test
  void skipsInvalidRowsAndContinuesScrollingUntilTheEligibleLimit() throws Exception {
    var client = mock(QdrantClient.class);
    when(client.collectionExistsAsync("books")).thenReturn(immediateFuture(true));
    when(client.createPayloadIndexAsync(
            any(CreateFieldIndexCollection.class), any(java.time.Duration.class)))
        .thenReturn(immediateFuture(UpdateResult.getDefaultInstance()));
    var invalid =
        RetrievedPoint.newBuilder()
            .setId(id(1))
            .putPayload("bookId", value("invalid"))
            .putPayload("description", value(" "))
            .build();
    var valid =
        RetrievedPoint.newBuilder()
            .setId(id(2))
            .putPayload("bookId", value("valid"))
            .putPayload("description", value("Description"))
            .build();
    when(client.scrollAsync(any(ScrollPoints.class)))
        .thenReturn(
            immediateFuture(
                ScrollResponse.newBuilder().addResult(invalid).setNextPageOffset(id(2)).build()),
            immediateFuture(ScrollResponse.newBuilder().addResult(valid).build()));
    var store =
        new QdrantShortDescriptionStore(
            client, new QdrantPointMapper(new Bm25SparseTextEncoder()), "books");
    assertThat(store.findRecordsWithoutShortDescription(1))
        .extracting(book -> book.record().id())
        .containsExactly("valid");
    var pages = ArgumentCaptor.forClass(ScrollPoints.class);
    verify(client, times(2)).scrollAsync(pages.capture());
    assertThat(pages.getAllValues().get(1).getOffset()).isEqualTo(id(2));
  }
}
