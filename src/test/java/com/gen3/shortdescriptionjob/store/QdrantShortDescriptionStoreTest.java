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
import com.gen3.shortdescriptionjob.ShortDescriptionMode;
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
            .putPayload("id", value("invalid"))
            .putPayload("description", value(" "))
            .build();
    var valid =
        RetrievedPoint.newBuilder()
            .setId(id(2))
            .putPayload("id", value("valid"))
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
    var records = store.findRecordsForShortDescription(1, ShortDescriptionMode.MISSING_ONLY);
    assertThat(records).extracting(book -> book.record().id()).containsExactly("valid");
    assertThat(records.getFirst().storageReference())
        .isEqualTo(java.util.Base64.getEncoder().encodeToString(id(2).toByteArray()));
    var pages = ArgumentCaptor.forClass(ScrollPoints.class);
    verify(client, times(2)).scrollAsync(pages.capture());
    assertThat(pages.getAllValues().get(1).getOffset()).isEqualTo(id(2));
    assertThat(pages.getAllValues())
        .allSatisfy(
            page ->
                assertThat(page.getFilter().getMustList())
                    .containsExactly(
                        io.qdrant.client.ConditionFactory.isEmpty("short_description")));
    var index = ArgumentCaptor.forClass(CreateFieldIndexCollection.class);
    verify(client).createPayloadIndexAsync(index.capture(), any(java.time.Duration.class));
    assertThat(index.getValue().getFieldName()).isEqualTo("short_description");
    assertThat(index.getValue().getFieldType())
        .isEqualTo(io.qdrant.client.grpc.Points.FieldType.FieldTypeKeyword);
    assertThat(store.findRecordsForShortDescription(0, ShortDescriptionMode.MISSING_ONLY))
        .isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(ShortDescriptionMode.class)
  void writesOnlyTheSummaryAtTheOriginalPointWithTheSelectedOverwritePolicy(
      ShortDescriptionMode mode) throws Exception {
    var client = mock(QdrantClient.class);
    when(client.collectionExistsAsync("books")).thenReturn(immediateFuture(true));
    when(client.batchUpdateAsync(
            org.mockito.ArgumentMatchers.eq("books"), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(immediateFuture(java.util.List.of(UpdateResult.getDefaultInstance())));
    var pointId = id(42);
    var store =
        new QdrantShortDescriptionStore(
            client, new QdrantPointMapper(new Bm25SparseTextEncoder()), "books");
    store.updateShortDescriptions(
        java.util.List.of(
            new AudiobookEnrichmentStore.ShortDescriptionUpdate(
                java.util.Base64.getEncoder().encodeToString(pointId.toByteArray()), "Summary")),
        mode);
    ArgumentCaptor<java.util.List<io.qdrant.client.grpc.Points.PointsUpdateOperation>> operations =
        ArgumentCaptor.captor();
    verify(client).batchUpdateAsync(org.mockito.ArgumentMatchers.eq("books"), operations.capture());
    assertThat(operations.getValue()).hasSize(1);
    var write = operations.getValue().getFirst().getSetPayload();
    assertThat(write.getPayloadMap())
        .containsExactlyEntriesOf(java.util.Map.of("short_description", value("Summary")));
    var conditions = write.getPointsSelector().getFilter().getMustList();
    if (mode == ShortDescriptionMode.MISSING_ONLY) {
      assertThat(conditions)
          .containsExactlyInAnyOrder(
              io.qdrant.client.ConditionFactory.hasId(pointId),
              io.qdrant.client.ConditionFactory.isEmpty("short_description"));
    } else {
      assertThat(conditions).containsExactly(io.qdrant.client.ConditionFactory.hasId(pointId));
    }
  }
}
