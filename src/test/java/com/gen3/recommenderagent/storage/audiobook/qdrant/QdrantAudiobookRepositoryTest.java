package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilterConditions;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Points.CreateFieldIndexCollection;
import io.qdrant.client.grpc.Points.DenseVector;
import io.qdrant.client.grpc.Points.FieldType;
import io.qdrant.client.grpc.Points.NamedVectorsOutput;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.Points.VectorOutput;
import io.qdrant.client.grpc.Points.VectorsOutput;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Covers Qdrant repository behavior that does not require a running database. */
class QdrantAudiobookRepositoryTest {

  /** Verifies that Qdrant's dot-product score remains attached to the returned audiobook. */
  @Test
  void returnsDatabaseIndependentCandidateWithQdrantScore() throws Exception {
    QdrantClient client = mock(QdrantClient.class);
    SparseTextEncoder sparseEncoder = new Bm25SparseTextEncoder();
    QdrantPointMapper mapper = new QdrantPointMapper(sparseEncoder);
    AudiobookRecord book =
        new AudiobookRecord("book-1", "catalogue", "Title", List.of("Author"), "Summary");
    PointStruct point = mapper.toPoint(new AudiobookEmbedding(book, new float[] {0.6f, 0.8f}));
    ScoredPoint result =
        ScoredPoint.newBuilder()
            .setId(point.getId())
            .setScore(0.92f)
            .setVectors(
                VectorsOutput.newBuilder()
                    .setVectors(
                        NamedVectorsOutput.newBuilder()
                            .putVectors(
                                QdrantPointMapper.DENSE_VECTOR,
                                VectorOutput.newBuilder()
                                    .setDense(DenseVector.newBuilder().addData(0.6f).addData(0.8f))
                                    .build())))
            .putAllPayload(point.getPayloadMap())
            .build();
    when(client.collectionExistsAsync("audiobooks")).thenReturn(Futures.immediateFuture(true));
    when(client.queryAsync(any(QueryPoints.class)))
        .thenReturn(Futures.immediateFuture(List.of(result)));
    QdrantAudiobookRepository repository =
        new QdrantAudiobookRepository(client, mapper, sparseEncoder, "audiobooks", 2);

    List<AudiobookCandidate> candidates =
        repository.searchSemantic(new float[] {0.6f, 0.8f}, AudiobookFilters.empty(), 5);
    repository.searchSemantic(new float[] {0.6f, 0.8f}, AudiobookFilters.empty(), 5);

    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().audiobook()).isEqualTo(book);
    assertThat(candidates.getFirst().score()).isEqualTo(0.92, within(0.0001));
    assertThat(candidates.getFirst().embedding()).containsExactly(0.6f, 0.8f);
    verify(client, times(1)).collectionExistsAsync("audiobooks");
    verify(client, never()).createCollectionAsync(any(CreateCollection.class));
    verify(client, never()).createPayloadIndexAsync(any(CreateFieldIndexCollection.class), any());
  }

  /** Verifies a favourite book ID resolves to the unnamed dense vector stored in Qdrant. */
  @Test
  void findsEmbeddingByCatalogueBookId() throws Exception {
    QdrantClient client = mock(QdrantClient.class);
    SparseTextEncoder sparseEncoder = new Bm25SparseTextEncoder();
    QdrantPointMapper mapper = new QdrantPointMapper(sparseEncoder);
    RetrievedPoint result =
        RetrievedPoint.newBuilder()
            .setId(mapper.pointId("book-9"))
            .setVectors(
                VectorsOutput.newBuilder()
                    .setVectors(
                        NamedVectorsOutput.newBuilder()
                            .putVectors(
                                QdrantPointMapper.DENSE_VECTOR,
                                VectorOutput.newBuilder()
                                    .setDense(DenseVector.newBuilder().addData(0.6f).addData(0.8f))
                                    .build())))
            .build();
    when(client.collectionExistsAsync("audiobooks")).thenReturn(Futures.immediateFuture(true));
    when(client.retrieveAsync(eq("audiobooks"), anyList(), eq(false), eq(true), isNull()))
        .thenReturn(Futures.immediateFuture(List.of(result)));
    QdrantAudiobookRepository repository =
        new QdrantAudiobookRepository(client, mapper, sparseEncoder, "audiobooks", 2);

    Optional<float[]> vector = repository.findEmbeddingByBookId("book-9");

    assertThat(vector).isPresent();
    assertThat(vector.orElseThrow()).containsExactly(0.6f, 0.8f);
  }

  @Test
  void readsPreferenceImportVectorsInBoundedBatches() throws Exception {
    QdrantClient client = mock(QdrantClient.class);
    SparseTextEncoder sparseEncoder = new Bm25SparseTextEncoder();
    QdrantPointMapper mapper = new QdrantPointMapper(sparseEncoder);
    when(client.collectionExistsAsync("audiobooks")).thenReturn(Futures.immediateFuture(true));
    when(client.retrieveAsync(eq("audiobooks"), anyList(), eq(false), eq(true), isNull()))
        .thenReturn(Futures.immediateFuture(List.of()));
    QdrantAudiobookRepository repository =
        new QdrantAudiobookRepository(client, mapper, sparseEncoder, "audiobooks", 2);

    repository.findEmbeddingsByBookIds(
        IntStream.range(0, 101).mapToObj(index -> "book-" + index).toList());

    verify(client, times(2))
        .retrieveAsync(eq("audiobooks"), anyList(), eq(false), eq(true), isNull());
  }

  @Test
  void loadsOnlyRecordsMissingShortDescriptions() throws Exception {
    QdrantClient client = mock(QdrantClient.class);
    SparseTextEncoder sparseEncoder = new Bm25SparseTextEncoder();
    QdrantPointMapper mapper = new QdrantPointMapper(sparseEncoder);
    PointStruct point =
        mapper.toPoint(
            new AudiobookEmbedding(
                new AudiobookRecord("book-3", "NLS", "Title", List.of("Author"), "Summary"),
                new float[] {0.6f, 0.8f}));
    RetrievedPoint result =
        RetrievedPoint.newBuilder()
            .setId(point.getId())
            .putAllPayload(point.getPayloadMap())
            .build();
    when(client.collectionExistsAsync("audiobooks")).thenReturn(Futures.immediateFuture(true));
    when(client.scrollAsync(any(ScrollPoints.class)))
        .thenReturn(Futures.immediateFuture(ScrollResponse.newBuilder().addResult(result).build()));
    when(client.createPayloadIndexAsync(any(CreateFieldIndexCollection.class), any()))
        .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
    QdrantAudiobookRepository repository =
        new QdrantAudiobookRepository(client, mapper, sparseEncoder, "audiobooks", 2);

    List<AudiobookEnrichmentStore.StoredAudiobook> records =
        repository.findRecordsWithoutShortDescription(10);

    assertThat(records).hasSize(1);
    assertThat(records.getFirst().record().id()).isEqualTo("book-3");
    assertThat(records.getFirst().storageReference())
        .isEqualTo(Base64.getEncoder().encodeToString(point.getId().toByteArray()));
    ArgumentCaptor<ScrollPoints> request = ArgumentCaptor.forClass(ScrollPoints.class);
    verify(client).scrollAsync(request.capture());
    assertThat(request.getValue().getFilter().toString()).contains("short_description");
    ArgumentCaptor<CreateFieldIndexCollection> index =
        ArgumentCaptor.forClass(CreateFieldIndexCollection.class);
    verify(client).createPayloadIndexAsync(index.capture(), any());
    assertThat(index.getValue().getFieldName()).isEqualTo("short_description");
    assertThat(index.getValue().getFieldType()).isEqualTo(FieldType.FieldTypeKeyword);
  }

  @Test
  void storesShortDescriptionsOnlyWhenTheyAreStillMissing() throws Exception {
    QdrantClient client = mock(QdrantClient.class);
    SparseTextEncoder sparseEncoder = new Bm25SparseTextEncoder();
    QdrantPointMapper mapper = new QdrantPointMapper(sparseEncoder);
    when(client.collectionExistsAsync("audiobooks")).thenReturn(Futures.immediateFuture(true));
    when(client.batchUpdateAsync(eq("audiobooks"), anyList()))
        .thenReturn(Futures.immediateFuture(List.of(UpdateResult.getDefaultInstance())));
    QdrantAudiobookRepository repository =
        new QdrantAudiobookRepository(client, mapper, sparseEncoder, "audiobooks", 2);

    repository.updateShortDescriptions(
        List.of(
            new AudiobookEnrichmentStore.ShortDescriptionUpdate(
                Base64.getEncoder().encodeToString(mapper.pointId("book-3").toByteArray()),
                "A short description.")));

    ArgumentCaptor<List<io.qdrant.client.grpc.Points.PointsUpdateOperation>> operations =
        ArgumentCaptor.forClass(List.class);
    verify(client).batchUpdateAsync(eq("audiobooks"), operations.capture());
    assertThat(operations.getValue()).hasSize(1);
    assertThat(
            operations
                .getValue()
                .getFirst()
                .getSetPayload()
                .getPointsSelector()
                .getFilter()
                .toString())
        .contains("short_description", "is_empty");
  }

  @Test
  void createsCollectionOnlyForExplicitIndexing() throws Exception {
    QdrantClient client = mock(QdrantClient.class);
    SparseTextEncoder sparseEncoder = new Bm25SparseTextEncoder();
    QdrantPointMapper mapper = new QdrantPointMapper(sparseEncoder);
    when(client.collectionExistsAsync("audiobooks")).thenReturn(Futures.immediateFuture(false));
    when(client.createCollectionAsync(any()))
        .thenReturn(
            Futures.immediateFuture(
                io.qdrant.client.grpc.Collections.CollectionOperationResponse
                    .getDefaultInstance()));
    when(client.createPayloadIndexAsync(any(CreateFieldIndexCollection.class), any()))
        .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
    when(client.upsertAsync(eq("audiobooks"), anyList()))
        .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
    QdrantAudiobookRepository repository =
        new QdrantAudiobookRepository(client, mapper, sparseEncoder, "audiobooks", 2);

    repository.indexEmbeddings(
        List.of(
            new AudiobookEmbedding(
                new AudiobookRecord("book-1", "source", "Title", List.of(), "Summary"),
                new float[] {0.6f, 0.8f})));

    ArgumentCaptor<CreateFieldIndexCollection> index =
        ArgumentCaptor.forClass(CreateFieldIndexCollection.class);
    verify(client, times(7)).createPayloadIndexAsync(index.capture(), any());
    assertThat(index.getAllValues())
        .extracting(CreateFieldIndexCollection::getFieldName)
        .contains("bookId")
        .doesNotContain("short_description");
  }

  @Test
  void doesNotCreateCollectionForSearch() {
    QdrantClient client = mock(QdrantClient.class);
    SparseTextEncoder sparseEncoder = new Bm25SparseTextEncoder();
    when(client.collectionExistsAsync("audiobooks")).thenReturn(Futures.immediateFuture(false));
    QdrantAudiobookRepository repository =
        new QdrantAudiobookRepository(
            client, new QdrantPointMapper(sparseEncoder), sparseEncoder, "audiobooks", 2);

    assertThatThrownBy(
            () -> repository.searchSemantic(new float[] {0.6f, 0.8f}, AudiobookFilters.empty(), 5))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("does not exist");

    verify(client, never()).createCollectionAsync(any());
  }

  @Test
  void mapsOnlyIndexedMetadataToQdrantConditions() throws Exception {
    QdrantClient client = mock(QdrantClient.class);
    SparseTextEncoder sparseEncoder = new Bm25SparseTextEncoder();
    when(client.collectionExistsAsync("audiobooks")).thenReturn(Futures.immediateFuture(true));
    when(client.queryAsync(any(QueryPoints.class))).thenReturn(Futures.immediateFuture(List.of()));
    QdrantAudiobookRepository repository =
        new QdrantAudiobookRepository(
            client, new QdrantPointMapper(sparseEncoder), sparseEncoder, "audiobooks", 2);
    AudiobookFilters filters =
        new AudiobookFilters(
            new AudiobookFilterConditions(
                List.of(), List.of(), "English", "NLS", null, null, 2020, null),
            new AudiobookFilterConditions(
                List.of(), List.of(), "French", "Legacy", null, null, null, 2000),
            List.of("shown-1", "shown-2"));

    repository.searchByFilters(filters, 5);

    ArgumentCaptor<QueryPoints> request = ArgumentCaptor.forClass(QueryPoints.class);
    verify(client).queryAsync(request.capture());
    assertThat(request.getValue().getFilter().getMustCount()).isEqualTo(3);
    assertThat(request.getValue().getFilter().getMustNotCount()).isEqualTo(4);
    assertThat(request.getValue().getFilter().toString())
        .contains("language", "English", "source", "NLS", "publicationYear", "French", "Legacy")
        .contains("bookId", "shown-1", "shown-2");
  }
}
