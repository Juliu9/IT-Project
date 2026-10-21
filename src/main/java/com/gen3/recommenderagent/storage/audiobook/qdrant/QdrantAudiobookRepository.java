package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookSchema.SHORT_DESCRIPTION;
import static io.qdrant.client.ConditionFactory.hasId;
import static io.qdrant.client.ConditionFactory.isEmpty;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookCandidateSearch;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookVectorRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.CreateFieldIndexCollection;
import io.qdrant.client.grpc.Points.DenseVector;
import io.qdrant.client.grpc.Points.FieldType;
import io.qdrant.client.grpc.Points.PointsSelector;
import io.qdrant.client.grpc.Points.PointsUpdateOperation;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.Query;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.SparseVector;
import io.qdrant.client.grpc.Points.VectorInput;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    implements AudiobookCandidateSearch, AudiobookEnrichmentStore, AudiobookVectorRepository {

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

  @Value("${qdrant.url:http://localhost:6333}")
  private String endpoint = "http://localhost:6333";

  @Value("${qdrant.grpc-port:6334}")
  private int grpcPort = 6334;

  @Override
  public DatasetIdentity datasetIdentity() {
    String url = endpoint.contains("://") ? endpoint.trim() : "http://" + endpoint.trim();
    URI uri = URI.create(url);
    return new DatasetIdentity(
        "qdrant",
        uri.getScheme().toLowerCase(Locale.ROOT)
            + "://"
            + uri.getHost().toLowerCase(Locale.ROOT)
            + ":"
            + grpcPort
            + "/"
            + collection);
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
    int prefetchLimit = limit * 4;
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
            .setWithVectors(io.qdrant.client.WithVectorsSelectorFactory.enable(true))
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

  /** Upserts one audiobook point, using a deterministic point ID for safe reruns. */
  @Override
  public void indexEmbedding(AudiobookRecord record, float[] vector) throws IOException {
    indexEmbeddings(List.of(new AudiobookEmbedding(record, vector)));
  }

  /** Upserts a batch of audiobook points and waits until Qdrant accepts the operation. */
  @Override
  public void indexEmbeddings(List<AudiobookEmbedding> embeddings) throws IOException {
    if (embeddings == null || embeddings.isEmpty()) {
      return;
    }
    List<AudiobookEmbedding> valid =
        embeddings.stream()
            .filter(item -> item != null && item.record() != null)
            .filter(item -> item.vector() != null && item.vector().length > 0)
            .toList();
    if (valid.isEmpty()) {
      return;
    }
    valid.forEach(item -> validateDimension(item.vector()));
    initialize();
    await(
        client.upsertAsync(collection, valid.stream().map(mapper::toPoint).toList()),
        "write audiobook vectors to Qdrant");
  }

  /** Stores generated short descriptions without recalculating or rewriting the book vectors. */
  @Override
  public void updateShortDescriptions(List<ShortDescriptionUpdate> updates) throws IOException {
    if (updates == null || updates.isEmpty()) {
      return;
    }
    requireCollection();
    List<PointsUpdateOperation> operations = new ArrayList<>();
    for (ShortDescriptionUpdate update : updates) {
      if (update != null && hasText(update.storageReference()) && hasText(update.description())) {
        operations.add(
            setMissingPayload(
                pointId(update.storageReference()),
                SHORT_DESCRIPTION,
                io.qdrant.client.ValueFactory.value(update.description())));
      }
    }
    updatePayloads(operations);
  }

  private PointsUpdateOperation setMissingPayload(
      io.qdrant.client.grpc.Common.PointId pointId,
      String field,
      io.qdrant.client.grpc.JsonWithInt.Value value) {
    PointsSelector selector =
        PointsSelector.newBuilder()
            .setFilter(Filter.newBuilder().addMust(hasId(pointId)).addMust(isEmpty(field)))
            .build();
    return PointsUpdateOperation.newBuilder()
        .setSetPayload(
            PointsUpdateOperation.SetPayload.newBuilder()
                .putPayload(field, value)
                .setPointsSelector(selector))
        .build();
  }

  /** Loads Qdrant records that still need a generated short description. */
  @Override
  public List<StoredAudiobook> findRecordsWithoutShortDescription(int maximumRecords)
      throws IOException {
    if (maximumRecords <= 0) {
      return List.of();
    }
    requireCollection();
    createPayloadIndex(SHORT_DESCRIPTION);
    List<StoredAudiobook> records = new ArrayList<>();
    io.qdrant.client.grpc.Common.PointId offset = null;
    while (records.size() < maximumRecords) {
      ScrollPoints.Builder request =
          ScrollPoints.newBuilder()
              .setCollectionName(collection)
              .setFilter(Filter.newBuilder().addMust(isEmpty(SHORT_DESCRIPTION)))
              .setLimit(Math.min(500, maximumRecords - records.size()))
              .setWithPayload(enable(true));
      if (offset != null) {
        request.setOffset(offset);
      }
      ScrollResponse page = await(client.scrollAsync(request.build()), "read audiobook records");
      records.addAll(
          page.getResultList().stream()
              .map(
                  point ->
                      new StoredAudiobook(
                          mapper.toRecord(point),
                          Base64.getEncoder().encodeToString(point.getId().toByteArray())))
              .toList());
      if (!page.hasNextPageOffset() || page.getResultCount() == 0) {
        break;
      }
      offset = page.getNextPageOffset();
    }
    return records;
  }

  private io.qdrant.client.grpc.Common.PointId pointId(String storageReference) throws IOException {
    try {
      return io.qdrant.client.grpc.Common.PointId.parseFrom(
          Base64.getDecoder().decode(storageReference));
    } catch (IllegalArgumentException exception) {
      throw new IOException("Invalid Qdrant storage reference", exception);
    }
  }

  private void updatePayloads(List<PointsUpdateOperation> operations) throws IOException {
    if (operations.isEmpty()) {
      return;
    }
    for (int start = 0; start < operations.size(); start += 100) {
      List<PointsUpdateOperation> batch =
          operations.subList(start, Math.min(start + 100, operations.size()));
      await(client.batchUpdateAsync(collection, batch), "store audiobook short descriptions");
    }
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
    for (int start = 0; start < ids.size(); start += 100) {
      List<String> batch = ids.subList(start, Math.min(start + 100, ids.size()));
      Map<io.qdrant.client.grpc.Common.PointId, String> bookIdsByPoint = new HashMap<>();
      for (String bookId : batch) {
        bookIdsByPoint.put(mapper.pointId(bookId), bookId);
      }
      List<RetrievedPoint> points =
          await(
              client.retrieveAsync(
                  collection, List.copyOf(bookIdsByPoint.keySet()), false, true, null),
              "retrieve audiobook vectors from Qdrant");
      for (RetrievedPoint point : points) {
        float[] vector = denseVector(point);
        String bookId = bookIdsByPoint.get(point.getId());
        if (hasText(bookId) && vector != null) {
          vectors.put(bookId, vector);
        }
      }
    }
    return vectors;
  }

  /** Creates named dense and sparse vectors plus indexes used by payload filters. */
  @Override
  public void initialize() throws IOException {
    ensureCollection(true);
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
            .setWithVectors(io.qdrant.client.WithVectorsSelectorFactory.enable(true));
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

  /** Adds a payload index required by Qdrant Cloud strict mode. */
  private void createPayloadIndex(String field) throws IOException {
    createPayloadIndex(field, QdrantAudiobookSchema.PAYLOAD_INDEXES.get(field));
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
    List<Float> values = vectors.get(QdrantPointMapper.DENSE_VECTOR).getDense().getDataList();
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
