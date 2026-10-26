package com.gen3.catalogue.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookCandidate;
import com.gen3.catalogue.model.AudiobookEmbedding;
import com.gen3.catalogue.model.AudiobookFilterConditions;
import com.gen3.catalogue.model.AudiobookFilters;
import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.model.StoredAudiobook;
import com.gen3.shortdescriptionjob.store.AudiobookEnrichmentStore;
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

  @Test
  void dottedInitialsMatchBothAuthorAndNarratorOrders() {
    var names =
        new AudiobookFilterConditions(
            List.of("J. K. Rowling"), List.of("J. K. Rowling"), null, null, null, null, null, null);
    var actual = QdrantAudiobookSchema.filter(new AudiobookFilters(names, names, List.of()));
    var expectedNames =
        List.of(
            "J. K. Rowling",
            "Rowling, J. K.",
            "J.K. Rowling",
            "Rowling, J.K.",
            "J K Rowling",
            "Rowling, J K",
            "J. K. Rowling.",
            "J.K. Rowling.",
            "J K Rowling.",
            "Rowling, J K.");
    var author = io.qdrant.client.ConditionFactory.matchKeywords("authors", expectedNames);
    var narrator = io.qdrant.client.ConditionFactory.matchKeywords("narrators", expectedNames);
    assertThat(actual)
        .isEqualTo(
            io.qdrant.client.grpc.Common.Filter.newBuilder()
                .addMust(author)
                .addMust(narrator)
                .addMustNot(author)
                .addMustNot(narrator)
                .build());
  }

  @Test
  void personFiltersIncludeAndExcludeBothNameOrders() {
    var conditions =
        new AudiobookFilterConditions(
            List.of("Suzanne Collins"),
            List.of("Fry, Stephen"),
            null,
            null,
            null,
            null,
            null,
            null);
    var actual =
        QdrantAudiobookSchema.filter(new AudiobookFilters(conditions, conditions, List.of()));
    var authors =
        io.qdrant.client.ConditionFactory.matchKeywords(
            "authors",
            List.of(
                "Suzanne Collins", "Collins, Suzanne", "Suzanne Collins.", "Collins, Suzanne."));
    var narrators =
        io.qdrant.client.ConditionFactory.matchKeywords(
            "narrators", List.of("Fry, Stephen", "Stephen Fry", "Fry, Stephen.", "Stephen Fry."));
    assertThat(actual)
        .isEqualTo(
            io.qdrant.client.grpc.Common.Filter.newBuilder()
                .addMust(authors)
                .addMust(narrators)
                .addMustNot(authors)
                .addMustNot(narrators)
                .build());
    assertThat(conditions.authors()).containsExactly("Suzanne Collins");
  }

  @Test
  void metadataLookupUsesPayloadBookIdsAndDoesNotRequestVectors() throws Exception {
    var client = mock(QdrantClient.class);
    var encoder = new Bm25SparseTextEncoder();
    when(client.collectionExistsAsync("books")).thenReturn(Futures.immediateFuture(true));
    var point =
        RetrievedPoint.newBuilder()
            .setId(io.qdrant.client.PointIdFactory.id(42))
            .putPayload("bookId", io.qdrant.client.ValueFactory.value("catalogue-id"))
            .putPayload("title", io.qdrant.client.ValueFactory.value("Title"))
            .putPayload(
                "authors",
                io.qdrant.client.ValueFactory.list(
                    java.util.List.of(io.qdrant.client.ValueFactory.value("Author"))))
            .build();
    when(client.scrollAsync(any(ScrollPoints.class)))
        .thenReturn(Futures.immediateFuture(ScrollResponse.newBuilder().addResult(point).build()));
    var repository =
        new QdrantAudiobookRepository(client, new QdrantPointMapper(encoder), encoder, "books", 2);
    var result = repository.findBooksByIds(List.of("catalogue-id"));
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().id()).isEqualTo("catalogue-id");
    assertThat(result.getFirst().authors()).containsExactly("Author");
    var request = ArgumentCaptor.forClass(ScrollPoints.class);
    verify(client).scrollAsync(request.capture());
    assertThat(request.getValue().getFilter().toString()).contains("bookId", "catalogue-id");
    assertThat(request.getValue().getWithVectors())
        .isEqualTo(io.qdrant.client.WithVectorsSelectorFactory.enable(false));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"semantic", "keyword", "hybrid", "filtered"})
  void candidateQueriesReturnOnlyTheDenseVectorNeededForRanking(String mode) throws Exception {
    var client = mock(QdrantClient.class);
    var encoder = new Bm25SparseTextEncoder();
    when(client.collectionExistsAsync("books")).thenReturn(Futures.immediateFuture(true));
    when(client.queryAsync(any(QueryPoints.class))).thenReturn(Futures.immediateFuture(List.of()));
    var repository =
        new QdrantAudiobookRepository(client, new QdrantPointMapper(encoder), encoder, "books", 2);
    switch (mode) {
      case "semantic" -> repository.searchSemantic(new float[] {1, 0}, AudiobookFilters.empty(), 5);
      case "keyword" -> repository.searchKeyword("fantasy", AudiobookFilters.empty(), 5);
      case "hybrid" ->
          repository.searchHybrid(new float[] {1, 0}, "fantasy", AudiobookFilters.empty(), 5);
      default ->
          repository.searchByFilters(
              new AudiobookFilters(
                  AudiobookFilterConditions.empty(),
                  AudiobookFilterConditions.empty(),
                  List.of("shown")),
              5);
    }
    var query = ArgumentCaptor.forClass(QueryPoints.class);
    verify(client).queryAsync(query.capture());
    assertThat(query.getValue().getWithVectors())
        .isEqualTo(
            io.qdrant.client.WithVectorsSelectorFactory.include(
                List.of(QdrantPointMapper.DENSE_VECTOR)));
  }

  @Test
  void hybridPrefetchesOneHundredPerSearchAndReturnsFiftyWithExclusionsIntact() throws Exception {
    var client = mock(QdrantClient.class);
    var encoder = new Bm25SparseTextEncoder();
    when(client.collectionExistsAsync("audiobooks")).thenReturn(Futures.immediateFuture(true));
    when(client.queryAsync(any(QueryPoints.class))).thenReturn(Futures.immediateFuture(List.of()));
    var repository =
        new QdrantAudiobookRepository(
            client, new QdrantPointMapper(encoder), encoder, "audiobooks", 2);
    var filters =
        new AudiobookFilters(
            AudiobookFilterConditions.empty(),
            AudiobookFilterConditions.empty(),
            List.of("shown-book"));
    repository.searchHybrid(new float[] {1, 0}, "fantasy", filters, 50);
    var query = ArgumentCaptor.forClass(QueryPoints.class);
    verify(client).queryAsync(query.capture());
    assertThat(query.getValue().getLimit()).isEqualTo(50);
    assertThat(query.getValue().getPrefetchList())
        .hasSize(2)
        .allSatisfy(
            prefetch -> {
              assertThat(prefetch.getLimit()).isEqualTo(100);
              assertThat(prefetch.getFilter()).isEqualTo(QdrantAudiobookSchema.filter(filters));
            });
    assertThat(query.getValue().getPrefetchList())
        .extracting(prefetch -> prefetch.getUsing())
        .containsExactly("dense", "keywords");
  }

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
            .setId(io.qdrant.client.PointIdFactory.id(98765L))
            .putPayload("bookId", io.qdrant.client.ValueFactory.value("book-9"))
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
    when(client.scrollAsync(any(ScrollPoints.class)))
        .thenReturn(Futures.immediateFuture(ScrollResponse.newBuilder().addResult(result).build()));
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
    when(client.scrollAsync(any(ScrollPoints.class)))
        .thenReturn(Futures.immediateFuture(ScrollResponse.getDefaultInstance()));
    QdrantAudiobookRepository repository =
        new QdrantAudiobookRepository(client, mapper, sparseEncoder, "audiobooks", 2);

    repository.findEmbeddingsByBookIds(
        IntStream.range(0, 101).mapToObj(index -> "book-" + index).toList());

    var requests = ArgumentCaptor.forClass(ScrollPoints.class);
    verify(client, times(2)).scrollAsync(requests.capture());
    assertThat(requests.getAllValues())
        .allSatisfy(
            request -> {
              assertThat(request.getLimit()).isEqualTo(100);
              assertThat(request.getFilter().getMust(0).getField().getKey()).isEqualTo("bookId");
            });
  }

  @Test
  void rejectsDuplicatesAcrossScrollPagesBeforeWritingOrChoosingAVector() throws Exception {
    var client = mock(QdrantClient.class);
    var encoder = new Bm25SparseTextEncoder();
    var mapper = new QdrantPointMapper(encoder);
    when(client.collectionExistsAsync("audiobooks")).thenReturn(Futures.immediateFuture(true));
    var first =
        RetrievedPoint.newBuilder()
            .setId(io.qdrant.client.PointIdFactory.id(1L))
            .putPayload("bookId", io.qdrant.client.ValueFactory.value("duplicate"))
            .build();
    var second = first.toBuilder().setId(io.qdrant.client.PointIdFactory.id(2L)).build();
    when(client.scrollAsync(any(ScrollPoints.class)))
        .thenAnswer(
            invocation -> {
              ScrollPoints request = invocation.getArgument(0);
              return Futures.immediateFuture(
                  request.hasOffset()
                      ? ScrollResponse.newBuilder().addResult(second).build()
                      : ScrollResponse.newBuilder()
                          .addResult(first)
                          .setNextPageOffset(second.getId())
                          .build());
            });
    var repository = new QdrantAudiobookRepository(client, mapper, encoder, "audiobooks", 2);
    var record =
        new AudiobookEmbedding(
            new AudiobookRecord("duplicate", "s", "Title", List.of(), "Summary"),
            new float[] {1, 0});
    assertThatThrownBy(() -> repository.findEmbeddingByBookId("duplicate"))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("Duplicate bookId");
    assertThatThrownBy(() -> repository.importStoredBooks(List.of(record)))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("Duplicate bookId");
    verify(client, never()).upsertAsync(anyString(), anyList());
  }

  @Test
  void rejectsDuplicateInputBookIdsBeforeWriting() throws Exception {
    var client = mock(QdrantClient.class);
    var encoder = new Bm25SparseTextEncoder();
    var repository =
        new QdrantAudiobookRepository(
            client, new QdrantPointMapper(encoder), encoder, "audiobooks", 2);
    var record =
        new AudiobookEmbedding(
            new AudiobookRecord("duplicate", "s", "Title", List.of(), "Summary"),
            new float[] {1, 0});
    assertThatThrownBy(() -> repository.importStoredBooks(List.of(record, record)))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("duplicate bookId");
    org.mockito.Mockito.verifyNoInteractions(client);
  }

  @Test
  void doesNotOverwriteAnUnrelatedRecordAtAGeneratedPointId() throws Exception {
    var client = mock(QdrantClient.class);
    var encoder = new Bm25SparseTextEncoder();
    var mapper = new QdrantPointMapper(encoder);
    when(client.collectionExistsAsync("audiobooks")).thenReturn(Futures.immediateFuture(true));
    when(client.scrollAsync(any(ScrollPoints.class)))
        .thenReturn(Futures.immediateFuture(ScrollResponse.getDefaultInstance()));
    when(client.retrieveAsync(eq("audiobooks"), anyList(), eq(false), eq(false), isNull()))
        .thenReturn(
            Futures.immediateFuture(
                List.of(RetrievedPoint.newBuilder().setId(mapper.pointId("new-book")).build())));
    var repository = new QdrantAudiobookRepository(client, mapper, encoder, "audiobooks", 2);
    var incoming =
        List.of(
            new AudiobookEmbedding(
                new AudiobookRecord("new-book", "s", "Title", List.of(), "Description"),
                new float[] {1, 0}));
    assertThatThrownBy(() -> repository.importStoredBooks(incoming))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("already occupied");
    verify(client, never()).upsertAsync(anyString(), anyList());
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

    List<StoredAudiobook> records =
        new com.gen3.shortdescriptionjob.store.QdrantShortDescriptionStore(
                client, mapper, "audiobooks")
            .findRecordsWithoutShortDescription(10);

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

    new com.gen3.shortdescriptionjob.store.QdrantShortDescriptionStore(client, mapper, "audiobooks")
        .updateShortDescriptions(
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
  void createsCollectionOnlyForExplicitMigration() throws Exception {
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

    when(client.scrollAsync(any(ScrollPoints.class)))
        .thenReturn(Futures.immediateFuture(ScrollResponse.getDefaultInstance()));
    when(client.retrieveAsync(eq("audiobooks"), anyList(), eq(false), eq(false), isNull()))
        .thenReturn(Futures.immediateFuture(List.of()));
    repository.importStoredBooks(
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
