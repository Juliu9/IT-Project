package com.gen3.catalogue.qdrant;

import static com.gen3.catalogue.qdrant.QdrantAudiobookSchema.SHORT_DESCRIPTION;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

import com.gen3.catalogue.model.AudiobookCandidate;
import com.gen3.catalogue.model.AudiobookEmbedding;
import com.gen3.catalogue.model.AudiobookFilters;
import com.gen3.catalogue.port.AudiobookCandidateSearch;
import com.gen3.catalogue.port.AudiobookVectorRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.CreateFieldIndexCollection;
import io.qdrant.client.grpc.Points.DenseVector;
import io.qdrant.client.grpc.Points.FieldType;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.Query;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SparseVector;
import io.qdrant.client.grpc.Points.VectorInput;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/** Stores and searches normalized audiobook vectors in a Qdrant collection. */
@ConditionalOnProperty(name = "audiobook.database", havingValue = "qdrant", matchIfMissing = true)
@Primary
@Repository
public class QdrantAudiobookRepository
    implements com.gen3.catalogue.port.AudiobookDetailsLookup,
        AudiobookCandidateSearch,
        AudiobookVectorRepository,
        com.gen3.catalogue.port.AudiobookTitleLookup {

  private final QdrantClient client;
  private final QdrantPointMapper mapper;
  private final SparseTextEncoder sparseEncoder;
  private final String collection;
  private final int vectorDimension;
  private final Object initializationLock = new Object();
  private volatile boolean initialized;

  /** Creates a repository for the configured Qdrant collection and embedding size. */
  public QdrantAudiobookRepository(
      QdrantClient client,
      QdrantPointMapper mapper,
      SparseTextEncoder sparseEncoder,
      @Value("${qdrant.collection:audiobooks_hybrid}") String collection,
      @Value("${qdrant.embedding-dimension:1536}") int vectorDimension) {
    this.client = client;
    this.mapper = mapper;
    this.sparseEncoder = sparseEncoder;
    this.collection = collection;
    this.vectorDimension = vectorDimension;
  }

  @Override
  public List<com.gen3.catalogue.model.AudiobookRecord> findBooksByIds(List<String> bookIds)
      throws IOException {
    if (bookIds.isEmpty()) {
      return List.of();
    }
    requireCollection();
    return findRecordsByBookIds(bookIds, false).values().stream().map(mapper::toRecord).toList();
  }

  @Override
  public List<com.gen3.catalogue.model.AudiobookTitle> findBooksByTitle(
      String title, String author, int limit) throws IOException {
    if (title == null || title.isBlank() || limit <= 0) {
      return List.of();
    }
    requireCollection();
    var filter =
        Filter.newBuilder()
            .addMust(
                io.qdrant.client.ConditionFactory.matchText(
                    QdrantAudiobookSchema.TITLE, title.strip()));
    if (author != null && !author.isBlank()) {
      var names = Filter.newBuilder();
      for (String name : com.gen3.catalogue.model.PersonNameNormalizer.variants(List.of(author))) {
        names.addShould(
            io.qdrant.client.ConditionFactory.matchText(QdrantAudiobookSchema.AUTHORS, name));
      }
      filter.addMust(io.qdrant.client.grpc.Common.Condition.newBuilder().setFilter(names));
    }
    var matches = new com.gen3.catalogue.model.AudiobookTitleMatches(title, author, limit);
    var request =
        io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
            .setCollectionName(collection)
            .setFilter(filter)
            .setLimit(100)
            .setWithPayload(
                io.qdrant.client.WithPayloadSelectorFactory.include(
                    List.of(
                        QdrantAudiobookSchema.ID,
                        QdrantAudiobookSchema.TITLE,
                        QdrantAudiobookSchema.AUTHORS)))
            .setWithVectors(io.qdrant.client.WithVectorsSelectorFactory.enable(false));
    while (true) {
      var page = await(client.scrollAsync(request.build()), "look up Qdrant book title");
      for (var point : page.getResultList()) {
        var book = mapper.toRecord(point);
        matches.add(
            new com.gen3.catalogue.model.AudiobookTitle(book.id(), book.title(), book.authors()));
      }
      if (matches.full() || !page.hasNextPageOffset()) {
        return matches.results();
      }
      request.setOffset(page.getNextPageOffset());
    }
  }

  /** Searches the named dense vector while applying structured payload filters. */
  @Override
  public List<AudiobookCandidate> searchSemantic(
      float[] queryVector, AudiobookFilters filters, int limit) throws IOException {
    if (queryVector == null || queryVector.length == 0 || limit <= 0) {
      return List.of();
    }
    validateDimension(queryVector);
    requireCollection();
    QueryPoints.Builder request = baseQuery(filters, limit);
    request.setUsing(QdrantPointMapper.DENSE_VECTOR).setQuery(denseQuery(queryVector));
    return query(request.build());
  }

  /** Searches the named sparse vector using BM25-style lexical term weights. */
  @Override
  public List<AudiobookCandidate> searchKeyword(String text, AudiobookFilters filters, int limit)
      throws IOException {
    SparseVectorData sparse = sparseEncoder.encode(text);
    if (sparse.isEmpty() || limit <= 0) {
      return List.of();
    }
    requireCollection();
    QueryPoints.Builder request = baseQuery(filters, limit);
    request.setUsing(QdrantPointMapper.KEYWORD_VECTOR).setQuery(sparseQuery(sparse));
    return query(request.build());
  }

  /** Fuses dense semantic and sparse lexical rankings with reciprocal rank fusion. */
  @Override
  public List<AudiobookCandidate> searchHybrid(
      float[] queryVector, String text, AudiobookFilters filters, int limit) throws IOException {
    if (queryVector == null || queryVector.length == 0 || limit <= 0) {
      return List.of();
    }
    validateDimension(queryVector);
    SparseVectorData sparse = sparseEncoder.encode(text);
    if (sparse.isEmpty()) {
      return searchSemantic(queryVector, filters, limit);
    }
    requireCollection();
    int prefetchLimit = limit * 2;
    Filter filter = QdrantAudiobookSchema.filter(filters);
    PrefetchQuery.Builder dense =
        PrefetchQuery.newBuilder()
            .setUsing(QdrantPointMapper.DENSE_VECTOR)
            .setQuery(denseQuery(queryVector))
            .setLimit(prefetchLimit);
    PrefetchQuery.Builder keywords =
        PrefetchQuery.newBuilder()
            .setUsing(QdrantPointMapper.KEYWORD_VECTOR)
            .setQuery(sparseQuery(sparse))
            .setLimit(prefetchLimit);
    if (filter != null) {
      dense.setFilter(filter);
      keywords.setFilter(filter);
    }
    QueryPoints request =
        QueryPoints.newBuilder()
            .setCollectionName(collection)
            .addPrefetch(dense)
            .addPrefetch(keywords)
            .setQuery(Query.newBuilder().setFusion(io.qdrant.client.grpc.Points.Fusion.RRF))
            .setLimit(limit)
            .setWithPayload(enable(true))
            .setWithVectors(
                io.qdrant.client.WithVectorsSelectorFactory.include(
                    List.of(QdrantPointMapper.DENSE_VECTOR)))
            .build();
    return query(request);
  }

  /** Returns payload-filtered audiobooks without vector similarity scoring. */
  @Override
  public List<AudiobookCandidate> searchByFilters(AudiobookFilters filters, int limit)
      throws IOException {
    if (filters == null || !filters.hasConditions() || limit <= 0) {
      return List.of();
    }
    requireCollection();
    return query(baseQuery(filters, limit).build());
  }

  /** Inserts missing books and fills only absent same-named catalogue fields on existing points. */
  public int importStoredBooks(List<AudiobookEmbedding> records) throws IOException {
    if (records.isEmpty()) {
      return 0;
    }
    validateUniqueBookIds(records);
    initialize();
    var existing =
        findRecordsByBookIds(
            records.stream().map(record -> record.record().id()).toList(), true, true);
    requireAvailablePointIds(records, existing);
    var points = new ArrayList<io.qdrant.client.grpc.Points.PointStruct>();
    var patches = new ArrayList<io.qdrant.client.grpc.Points.PointsUpdateOperation>();
    var vectors =
        io.qdrant.client.grpc.Points.UpdatePointVectors.newBuilder()
            .setCollectionName(collection)
            .setWait(true);
    int updated = 0;
    var unavailable = new LinkedHashMap<String, Integer>();
    int missingDates = 0;
    for (var record : records) {
      var source = mapper.toStoredPoint(record);
      var usable = new LinkedHashMap<String, io.qdrant.client.grpc.JsonWithInt.Value>();
      source
          .getPayloadMap()
          .forEach(
              (key, value) -> {
                if (usable(value)) {
                  usable.put(key, value);
                }
              });
      var current = existing.get(record.record().id());
      var missing = new LinkedHashMap<String, io.qdrant.client.grpc.JsonWithInt.Value>();
      usable.forEach(
          (key, value) -> {
            if (current == null || !usable(current.getPayloadMap().get(key))) {
              missing.put(key, value);
            }
          });
      for (String field :
          List.of(
              "id",
              "source",
              "title",
              "authors",
              "description",
              "narrators",
              "language",
              "duration_minutes",
              "pubdate",
              "short_description")) {
        if (!usable(usable.get(field))
            && (current == null || !usable(current.getPayloadMap().get(field)))) {
          unavailable.merge(field, 1, Integer::sum);
        }
      }
      if (record.record().pubdate() == null) {
        missingDates++;
      }
      boolean fillVector =
          (current == null || denseVector(current) == null)
              && record.vector() != null
              && record.vector().length > 0;
      if (fillVector) {
        validateDimension(record.vector());
        for (float value : record.vector()) {
          if (!Float.isFinite(value)) {
            throw new IOException("Stored source vector contains a non-finite value");
          }
        }
      }
      if (current == null) {
        points.add(source.toBuilder().clearPayload().putAllPayload(usable).build());
      } else {
        if (!missing.isEmpty()) {
          patches.add(
              io.qdrant.client.grpc.Points.PointsUpdateOperation.newBuilder()
                  .setSetPayload(
                      io.qdrant.client.grpc.Points.PointsUpdateOperation.SetPayload.newBuilder()
                          .putAllPayload(missing)
                          .setPointsSelector(
                              io.qdrant.client.grpc.Points.PointsSelector.newBuilder()
                                  .setFilter(
                                      Filter.newBuilder()
                                          .addMust(
                                              io.qdrant.client.ConditionFactory.hasId(
                                                  current.getId())))))
                  .build());
        }
        if (fillVector) {
          vectors.addPoints(
              io.qdrant.client.grpc.Points.PointVectors.newBuilder()
                  .setId(current.getId())
                  .setVectors(source.getVectors()));
        }
        if (!missing.isEmpty() || fillVector) {
          updated++;
        }
      }
    }
    if (!points.isEmpty()) {
      await(client.upsertAsync(collection, points), "copy existing catalogue data to Qdrant");
    }
    if (!patches.isEmpty()) {
      await(client.batchUpdateAsync(collection, patches), "fill missing catalogue payload");
    }
    if (vectors.getPointsCount() > 0) {
      await(client.updateVectorsAsync(vectors.build()), "fill missing stored catalogue vectors");
    }
    org.slf4j.LoggerFactory.getLogger(getClass())
        .info(
            "catalogue.migration.batch created={} updated={} skipped={} unavailableFields={} sourceMissingOrInvalidPubdate={}",
            points.size(),
            updated,
            records.size() - points.size() - updated,
            unavailable,
            missingDates);
    return points.size() + updated;
  }

  private boolean usable(io.qdrant.client.grpc.JsonWithInt.Value value) {
    if (value == null
        || value.hasNullValue()
        || value.getKindCase() == io.qdrant.client.grpc.JsonWithInt.Value.KindCase.KIND_NOT_SET) {
      return false;
    }
    if (value.hasStringValue()) {
      return !value.getStringValue().isBlank();
    }
    if (value.hasListValue()) {
      return value.getListValue().getValuesList().stream().anyMatch(this::usable);
    }
    return true;
  }

  /** Retrieves the stored vector for one favourite audiobook by its catalogue ID. */
  @Override
  public Optional<float[]> findEmbeddingByBookId(String bookId) throws IOException {
    if (!hasText(bookId)) {
      return Optional.empty();
    }
    return Optional.ofNullable(findEmbeddingsByBookIds(List.of(bookId)).get(bookId));
  }

  /** Retrieves many catalogue vectors in bounded gRPC calls for preference imports. */
  @Override
  public Map<String, float[]> findEmbeddingsByBookIds(Collection<String> bookIds)
      throws IOException {
    if (bookIds == null || bookIds.isEmpty()) {
      return Map.of();
    }
    List<String> ids = bookIds.stream().filter(this::hasText).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    requireCollection();
    Map<String, float[]> vectors = new LinkedHashMap<>();
    for (var entry : findRecordsByBookIds(ids, true).entrySet()) {
      float[] vector = denseVector(entry.getValue());
      if (vector != null) {
        vectors.put(entry.getKey(), vector);
      }
    }
    return vectors;
  }

  private void requireAvailablePointIds(
      List<AudiobookEmbedding> incoming, Map<String, RetrievedPoint> existing) throws IOException {
    var ids =
        incoming.stream()
            .filter(item -> !existing.containsKey(item.record().id()))
            .map(item -> mapper.pointId(item.record().id()))
            .toList();
    for (int start = 0; start < ids.size(); start += 100) {
      var occupied =
          await(
              client.retrieveAsync(
                  collection,
                  ids.subList(start, Math.min(start + 100, ids.size())),
                  false,
                  false,
                  null),
              "check new Qdrant point IDs");
      if (!occupied.isEmpty()) {
        throw new IOException(
            "A generated point ID is already occupied; existing records were not overwritten");
      }
    }
  }

  private void validateUniqueBookIds(List<AudiobookEmbedding> records) throws IOException {
    var ids = new java.util.HashSet<String>();
    for (var record : records) {
      String id = record.record().id();
      if (!hasText(id) || !ids.add(id)) {
        throw new IOException("Missing or duplicate catalogue id in input batch: " + id);
      }
    }
  }

  /** Book IDs are catalogue keys; existing Qdrant point IDs are opaque. */
  private Map<String, RetrievedPoint> findRecordsByBookIds(List<String> ids, boolean withVectors)
      throws IOException {
    return findRecordsByBookIds(ids, withVectors, !withVectors);
  }

  private Map<String, RetrievedPoint> findRecordsByBookIds(
      List<String> ids, boolean withVectors, boolean fullPayload) throws IOException {
    Map<String, RetrievedPoint> records = new LinkedHashMap<>();
    int batchSize = withVectors ? 500 : 100;
    for (int start = 0; start < ids.size(); start += batchSize) {
      var batch = ids.subList(start, Math.min(start + batchSize, ids.size()));
      var request =
          io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
              .setCollectionName(collection)
              .setFilter(
                  Filter.newBuilder()
                      .addMust(
                          io.qdrant.client.ConditionFactory.matchKeywords(
                              QdrantAudiobookSchema.ID, batch)))
              .setLimit(batchSize)
              .setWithPayload(
                  fullPayload
                      ? enable(true)
                      : io.qdrant.client.WithPayloadSelectorFactory.include(
                          List.of(QdrantAudiobookSchema.ID)))
              .setWithVectors(
                  withVectors
                      ? io.qdrant.client.WithVectorsSelectorFactory.include(
                          List.of(QdrantPointMapper.DENSE_VECTOR))
                      : io.qdrant.client.WithVectorsSelectorFactory.enable(false));
      while (true) {
        var page =
            await(client.scrollAsync(request.build()), "resolve catalogue book IDs in Qdrant");
        for (var point : page.getResultList()) {
          String bookId = point.getPayloadMap().get(QdrantAudiobookSchema.ID).getStringValue();
          if (records.putIfAbsent(bookId, point) != null) {
            throw new IOException(
                "Duplicate catalogue id in Qdrant collection " + collection + ": " + bookId);
          }
        }
        if (!page.hasNextPageOffset()) {
          break;
        }
        request.setOffset(page.getNextPageOffset());
      }
    }
    return records;
  }

  /** Creates named dense and sparse vectors plus indexes used by payload filters. */
  public void initialize() throws IOException {
    ensureCollection(true);
  }

  /** Explicit migration setup also prepares indexes on an existing renamed collection. */
  public void prepareMigration() throws IOException {
    initialize();
    createPayloadIndex(QdrantAudiobookSchema.ID, FieldType.FieldTypeKeyword);
    createPayloadIndex(QdrantAudiobookSchema.DURATION_MINUTES, FieldType.FieldTypeInteger);
    createPayloadIndex(QdrantAudiobookSchema.PUBDATE, FieldType.FieldTypeDatetime);
  }

  private void requireCollection() throws IOException {
    ensureCollection(false);
  }

  private void ensureCollection(boolean createIfMissing) throws IOException {
    if (initialized) {
      return;
    }
    synchronized (initializationLock) {
      if (initialized) {
        return;
      }
      if (!await(client.collectionExistsAsync(collection), "check Qdrant collection")) {
        if (!createIfMissing) {
          throw new IOException("Qdrant collection does not exist: " + collection);
        }
        createCollection();
      }
      initialized = true;
    }
  }

  /** Creates the named dense and sparse vectors plus their filterable payload indexes. */
  private void createCollection() throws IOException {
    var request = QdrantAudiobookSchema.collection(collection, vectorDimension);
    await(client.createCollectionAsync(request), "create hybrid Qdrant collection");
    for (var index : QdrantAudiobookSchema.PAYLOAD_INDEXES.entrySet()) {
      // Short-description indexing remains opt-in with description enrichment.
      if (!index.getKey().equals(SHORT_DESCRIPTION)) {
        createPayloadIndex(index.getKey(), index.getValue());
      }
    }
  }

  /** Executes a universal Qdrant query and maps its fused score to the shared candidate type. */
  private List<AudiobookCandidate> query(QueryPoints request) throws IOException {
    List<ScoredPoint> matches = await(client.queryAsync(request), "query Qdrant");
    return matches.stream()
        .map(
            point ->
                new AudiobookCandidate(
                    mapper.toRecord(point), (double) point.getScore(), mapper.toDenseVector(point)))
        .toList();
  }

  /** Builds common collection, filter, limit, and payload settings for every query mode. */
  private QueryPoints.Builder baseQuery(AudiobookFilters filters, int limit) {
    QueryPoints.Builder request =
        QueryPoints.newBuilder()
            .setCollectionName(collection)
            .setLimit(limit)
            .setWithPayload(enable(true))
            .setWithVectors(
                io.qdrant.client.WithVectorsSelectorFactory.include(
                    List.of(QdrantPointMapper.DENSE_VECTOR)));
    Filter filter = QdrantAudiobookSchema.filter(filters);
    return filter == null ? request : request.setFilter(filter);
  }

  /** Converts a dense embedding to Qdrant's universal nearest-neighbour query. */
  private Query denseQuery(float[] vector) {
    return Query.newBuilder()
        .setNearest(
            VectorInput.newBuilder()
                .setDense(DenseVector.newBuilder().addAllData(floatList(vector))))
        .build();
  }

  /** Converts lexical indices and weights to Qdrant's universal sparse query. */
  private Query sparseQuery(SparseVectorData vector) {
    return Query.newBuilder()
        .setNearest(
            VectorInput.newBuilder()
                .setSparse(
                    SparseVector.newBuilder()
                        .addAllIndices(vector.indices())
                        .addAllValues(vector.values())))
        .build();
  }

  private void createPayloadIndex(String field, FieldType type) throws IOException {
    CreateFieldIndexCollection request =
        CreateFieldIndexCollection.newBuilder()
            .setCollectionName(collection)
            .setFieldName(field)
            .setFieldType(type)
            .setWait(true)
            .build();
    await(
        client.createPayloadIndexAsync(request, Duration.ofSeconds(30)),
        "create Qdrant payload index for " + field);
  }

  /** Checks that query and stored vectors match the collection schema. */
  private void validateDimension(float[] vector) {
    if (vector.length != vectorDimension) {
      throw new IllegalArgumentException(
          "Embedding dimension "
              + vector.length
              + " does not match Qdrant dimension "
              + vectorDimension);
    }
  }

  /** Converts a primitive embedding into the boxed list required by the generated gRPC API. */
  private List<Float> floatList(float[] vector) {
    List<Float> values = new ArrayList<>(vector.length);
    for (float value : vector) {
      values.add(value);
    }
    return values;
  }

  private float[] denseVector(RetrievedPoint point) {
    if (point == null || !point.hasVectors()) {
      return null;
    }
    var vectors = point.getVectors().getVectors().getVectorsMap();
    if (!vectors.containsKey(QdrantPointMapper.DENSE_VECTOR)) {
      return null;
    }
    var dense = vectors.get(QdrantPointMapper.DENSE_VECTOR);
    List<Float> values = dense.getDense().getDataList();
    if (values.isEmpty()) {
      return null;
    }
    float[] vector = new float[values.size()];
    for (int index = 0; index < values.size(); index++) {
      vector[index] = values.get(index);
    }
    return vector;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /** Converts asynchronous Qdrant failures into the repository's checked I/O contract. */
  private <T> T await(java.util.concurrent.Future<T> future, String operation) throws IOException {
    try {
      return future.get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while trying to " + operation, exception);
    } catch (ExecutionException exception) {
      throw new IOException("Could not " + operation, exception.getCause());
    }
  }
}
