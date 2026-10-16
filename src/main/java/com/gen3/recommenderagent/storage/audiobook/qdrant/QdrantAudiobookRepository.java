package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static io.qdrant.client.ConditionFactory.hasId;
import static io.qdrant.client.ConditionFactory.isEmpty;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.ConditionFactory.range;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilterConditions;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookCandidateSearch;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookMetadataEnricher;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookVectorRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Range;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** Stores and searches normalized audiobook vectors in a Qdrant collection. */
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

  /** Searches the named dense vector while applying structured payload filters. */
  @Override
  public List<AudiobookCandidate> searchSemantic(
      float[] queryVector, AudiobookFilters filters, int limit) throws IOException {
    if (queryVector == null || queryVector.length == 0 || limit <= 0) {
      return List.of();
    }
    validateDimension(queryVector);
    initialize();
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
    initialize();
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
    initialize();
    int prefetchLimit = limit * 4;
    Filter filter = filter(filters);
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
    initialize();
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
    initialize();
    List<PointsUpdateOperation> operations = new ArrayList<>();
    for (ShortDescriptionUpdate update : updates) {
      if (update != null && hasText(update.storageReference()) && hasText(update.description())) {
        operations.add(
            setMissingPayload(
                pointId(update.storageReference()),
                "short_description",
                io.qdrant.client.ValueFactory.value(update.description())));
      }
    }
    updatePayloads(operations);
  }

  /** Adds fake metadata only to fields absent from existing Qdrant payloads. */
  @Override
  public int enrichMissingMetadata(AudiobookMetadataEnricher metadataEnricher) throws IOException {
    initialize();
    createPayloadIndex("publicationYear", FieldType.FieldTypeInteger);
    int updated = 0;
    io.qdrant.client.grpc.Common.PointId offset = null;
    while (true) {
      ScrollPoints.Builder request =
          ScrollPoints.newBuilder()
              .setCollectionName(collection)
              .setLimit(500)
              .setWithPayload(enable(true));
      if (offset != null) {
        request.setOffset(offset);
      }
      ScrollResponse page = await(client.scrollAsync(request.build()), "read audiobook records");
      List<PointsUpdateOperation> operations = new ArrayList<>();
      for (RetrievedPoint point : page.getResultList()) {
        AudiobookRecord existing = mapper.toRecord(point);
        List<PointsUpdateOperation> missingFields =
            missingMetadataUpdates(
                point.getId(), point.getPayloadMap(), metadataEnricher.enrich(existing));
        if (!missingFields.isEmpty()) {
          operations.addAll(missingFields);
          updated++;
        }
      }
      updatePayloads(operations);
      if (!page.hasNextPageOffset() || page.getResultCount() == 0) {
        return updated;
      }
      offset = page.getNextPageOffset();
    }
  }

  private List<PointsUpdateOperation> missingMetadataUpdates(
      io.qdrant.client.grpc.Common.PointId pointId,
      Map<String, io.qdrant.client.grpc.JsonWithInt.Value> existingPayload,
      AudiobookRecord enriched) {
    List<PointsUpdateOperation> operations = new ArrayList<>();
    if (!existingPayload.containsKey("narrators")) {
      operations.add(
          setMissingPayload(
              pointId,
              "narrators",
              io.qdrant.client.ValueFactory.list(
                  enriched.narrators().stream()
                      .map(io.qdrant.client.ValueFactory::value)
                      .toList())));
    }
    if (!existingPayload.containsKey("language")) {
      operations.add(
          setMissingPayload(
              pointId, "language", io.qdrant.client.ValueFactory.value(enriched.language())));
    }
    if (!existingPayload.containsKey("durationMinutes")) {
      operations.add(
          setMissingPayload(
              pointId,
              "durationMinutes",
              io.qdrant.client.ValueFactory.value(enriched.durationMinutes().longValue())));
    }
    if (!existingPayload.containsKey("publicationYear")) {
      operations.add(
          setMissingPayload(
              pointId,
              "publicationYear",
              io.qdrant.client.ValueFactory.value(enriched.publicationYear().longValue())));
    }
    return operations;
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
    initialize();
    createPayloadIndex("short_description", FieldType.FieldTypeKeyword);
    List<StoredAudiobook> records = new ArrayList<>();
    io.qdrant.client.grpc.Common.PointId offset = null;
    while (records.size() < maximumRecords) {
      ScrollPoints.Builder request =
          ScrollPoints.newBuilder()
              .setCollectionName(collection)
              .setFilter(Filter.newBuilder().addMust(isEmpty("short_description")))
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
    if (bookId == null || bookId.isBlank()) {
      return Optional.empty();
    }
    initialize();
    List<RetrievedPoint> points =
        await(
            client.retrieveAsync(collection, List.of(mapper.pointId(bookId)), false, true, null),
            "retrieve an audiobook vector from Qdrant");
    if (points.isEmpty() || !points.getFirst().hasVectors()) {
      return Optional.empty();
    }
    var namedVectors = points.getFirst().getVectors().getVectors().getVectorsMap();
    if (!namedVectors.containsKey(QdrantPointMapper.DENSE_VECTOR)) {
      return Optional.empty();
    }
    List<Float> values = namedVectors.get(QdrantPointMapper.DENSE_VECTOR).getDense().getDataList();
    float[] vector = new float[values.size()];
    for (int index = 0; index < values.size(); index++) {
      vector[index] = values.get(index);
    }
    return Optional.of(vector);
  }

  /** Creates named dense and sparse vectors plus indexes used by payload filters. */
  @Override
  public void initialize() throws IOException {
    if (initialized) {
      return;
    }
    synchronized (initializationLock) {
      if (initialized) {
        return;
      }
      boolean exists = await(client.collectionExistsAsync(collection), "check Qdrant collection");
      if (!exists) {
        createCollection();
      }
      initialized = true;
    }
  }

  /** Creates the named dense and sparse vectors plus their filterable payload indexes. */
  private void createCollection() throws IOException {
    Collections.VectorParams denseParams =
        Collections.VectorParams.newBuilder()
            .setSize(vectorDimension)
            .setDistance(Collections.Distance.Dot)
            .build();
    Collections.VectorsConfig vectors =
        Collections.VectorsConfig.newBuilder()
            .setParamsMap(
                Collections.VectorParamsMap.newBuilder()
                    .putMap(QdrantPointMapper.DENSE_VECTOR, denseParams))
            .build();
    Collections.SparseVectorParams sparseParams =
        Collections.SparseVectorParams.newBuilder().setModifier(Collections.Modifier.Idf).build();
    Collections.SparseVectorConfig sparseVectors =
        Collections.SparseVectorConfig.newBuilder()
            .putMap(QdrantPointMapper.KEYWORD_VECTOR, sparseParams)
            .build();
    Collections.CreateCollection request =
        Collections.CreateCollection.newBuilder()
            .setCollectionName(collection)
            .setVectorsConfig(vectors)
            .setSparseVectorsConfig(sparseVectors)
            .build();
    await(client.createCollectionAsync(request), "create hybrid Qdrant collection");
    createPayloadIndex("authors", FieldType.FieldTypeKeyword);
    createPayloadIndex("narrators", FieldType.FieldTypeKeyword);
    createPayloadIndex("bookId", FieldType.FieldTypeKeyword);
    createPayloadIndex("language", FieldType.FieldTypeKeyword);
    createPayloadIndex("source", FieldType.FieldTypeKeyword);
    createPayloadIndex("durationMinutes", FieldType.FieldTypeInteger);
    createPayloadIndex("publicationYear", FieldType.FieldTypeInteger);
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
    Filter filter = filter(filters);
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

  /** Converts structured application filters into Qdrant payload conditions. */
  private Filter filter(AudiobookFilters filters) {
    if (filters == null || !filters.hasConditions()) {
      return null;
    }
    Filter.Builder builder = Filter.newBuilder();
    addConditions(builder, filters.mustInclude(), false);
    addConditions(builder, filters.mustNotInclude(), true);
    if (filters.excludedBookIds() != null && !filters.excludedBookIds().isEmpty()) {
      builder.addMustNot(matchKeywords("bookId", filters.excludedBookIds()));
    }
    return builder.build();
  }

  private void addConditions(
      Filter.Builder builder, AudiobookFilterConditions conditions, boolean excluded) {
    if (conditions == null || !conditions.hasConditions()) {
      return;
    }
    if (conditions.authors() != null && !conditions.authors().isEmpty()) {
      addCondition(builder, matchKeywords("authors", conditions.authors()), excluded);
    }
    if (conditions.narrators() != null && !conditions.narrators().isEmpty()) {
      addCondition(builder, matchKeywords("narrators", conditions.narrators()), excluded);
    }
    if (conditions.language() != null && !conditions.language().isBlank()) {
      addCondition(builder, matchKeyword("language", conditions.language()), excluded);
    }
    if (conditions.source() != null && !conditions.source().isBlank()) {
      addCondition(builder, matchKeyword("source", conditions.source()), excluded);
    }
    if (conditions.minimumDurationMinutes() != null
        || conditions.maximumDurationMinutes() != null) {
      Range.Builder duration = Range.newBuilder();
      if (conditions.minimumDurationMinutes() != null) {
        duration.setGte(conditions.minimumDurationMinutes());
      }
      if (conditions.maximumDurationMinutes() != null) {
        duration.setLte(conditions.maximumDurationMinutes());
      }
      addCondition(builder, range("durationMinutes", duration.build()), excluded);
    }
  }

  private void addCondition(Filter.Builder builder, Condition condition, boolean excluded) {
    if (excluded) {
      builder.addMustNot(condition);
    } else {
      builder.addMust(condition);
    }
  }

  /** Adds a payload index required by Qdrant Cloud strict mode. */
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
