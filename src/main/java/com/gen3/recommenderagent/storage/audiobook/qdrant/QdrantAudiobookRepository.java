package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static io.qdrant.client.WithPayloadSelectorFactory.enable;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.ConditionFactory.range;

import com.gen3.recommenderagent.embedding.VectorMath;
import com.gen3.recommenderagent.ranker.retrieval.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRepository;
import com.gen3.recommenderagent.storage.audiobook.AudiobookSearchPage;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Range;
import io.qdrant.client.grpc.Collections;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** Stores and searches normalized audiobook vectors in a Qdrant collection. */
@Repository
public class QdrantAudiobookRepository implements AudiobookRepository {

  private final QdrantClient client;
  private final QdrantPointMapper mapper;
  private final SparseTextEncoder sparseEncoder;
  private final EmbeddingModel embeddingModel;
  private final String collection;
  private final int vectorDimension;

  /** Creates a repository for the configured Qdrant collection and embedding size. */
  public QdrantAudiobookRepository(
      QdrantClient client,
      QdrantPointMapper mapper,
      SparseTextEncoder sparseEncoder,
      EmbeddingModel embeddingModel,
      @Value("${qdrant.collection:audiobooks_hybrid}") String collection,
      @Value("${qdrant.embedding-dimension:1536}") int vectorDimension) {
    this.client = client;
    this.mapper = mapper;
    this.sparseEncoder = sparseEncoder;
    this.embeddingModel = embeddingModel;
    this.collection = collection;
    this.vectorDimension = vectorDimension;
  }

  /** Embeds plain query text and performs nearest-neighbour retrieval. */
  @Override
  public AudiobookSearchPage searchBooks(String query, int limit) throws IOException {
    if (query == null || query.isBlank() || limit <= 0) {
      return new AudiobookSearchPage(0, List.of());
    }
    return searchBooks(query, limit, VectorMath.normalize(embeddingModel.embed(query)));
  }

  /** Searches Qdrant with a normalized request vector and returns results in score order. */
  @Override
  public AudiobookSearchPage searchBooks(String query, int limit, float[] queryVector)
      throws IOException {
    List<AudiobookRecord> records =
        searchCandidates(query, limit, queryVector).stream()
            .map(AudiobookCandidate::audiobook)
            .toList();
    return new AudiobookSearchPage(records.size(), records);
  }

  /** Embeds plain query text and returns scored, database-independent candidates. */
  @Override
  public List<AudiobookCandidate> searchCandidates(String query, int limit) throws IOException {
    if (query == null || query.isBlank() || limit <= 0) {
      return List.of();
    }
    return searchCandidates(query, limit, VectorMath.normalize(embeddingModel.embed(query)));
  }

  /** Searches Qdrant and keeps its dot-product score alongside each audiobook. */
  @Override
  public List<AudiobookCandidate> searchCandidates(
      String query, int limit, float[] queryVector) throws IOException {
    return searchSemantic(queryVector, AudiobookFilters.empty(), limit);
  }

  /** Searches the named dense vector while applying structured payload filters. */
  public List<AudiobookCandidate> searchSemantic(
      float[] queryVector, AudiobookFilters filters, int limit) throws IOException {
    if (queryVector == null || queryVector.length == 0 || limit <= 0) {
      return List.of();
    }
    validateDimension(queryVector);
    ensureCollection();
    QueryPoints.Builder request = baseQuery(filters, limit);
    request.setUsing(QdrantPointMapper.DENSE_VECTOR).setQuery(denseQuery(queryVector));
    return query(request.build());
  }

  /** Searches the named sparse vector using BM25-style lexical term weights. */
  public List<AudiobookCandidate> searchKeyword(
      String text, AudiobookFilters filters, int limit) throws IOException {
    SparseVectorData sparse = sparseEncoder.encode(text);
    if (sparse.isEmpty() || limit <= 0) {
      return List.of();
    }
    ensureCollection();
    QueryPoints.Builder request = baseQuery(filters, limit);
    request.setUsing(QdrantPointMapper.KEYWORD_VECTOR).setQuery(sparseQuery(sparse));
    return query(request.build());
  }

  /** Fuses dense semantic and sparse lexical rankings with reciprocal rank fusion. */
  public List<AudiobookCandidate> searchHybrid(
      float[] queryVector, String text, AudiobookFilters filters, int limit) throws IOException {
    validateDimension(queryVector);
    SparseVectorData sparse = sparseEncoder.encode(text);
    if (sparse.isEmpty()) {
      return searchSemantic(queryVector, filters, limit);
    }
    ensureCollection();
    int prefetchLimit = Math.max(limit * 4, limit);
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
            .build();
    return query(request);
  }

  /** Returns payload-filtered audiobooks without vector similarity scoring. */
  public List<AudiobookCandidate> searchByFilters(AudiobookFilters filters, int limit)
      throws IOException {
    if (filters == null || !filters.hasConditions() || limit <= 0) {
      return List.of();
    }
    ensureCollection();
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
    ensureCollection();
    await(
        client.upsertAsync(collection, valid.stream().map(mapper::toPoint).toList()),
        "write audiobook vectors to Qdrant");
  }

  /** Retrieves the stored vector for one favourite audiobook by its catalogue ID. */
  @Override
  public Optional<float[]> findEmbeddingByBookId(String bookId) throws IOException {
    if (bookId == null || bookId.isBlank()) {
      return Optional.empty();
    }
    ensureCollection();
    List<RetrievedPoint> points =
        await(
            client.retrieveAsync(
                collection, List.of(mapper.pointId(bookId)), false, true, null),
            "retrieve an audiobook vector from Qdrant");
    if (points.isEmpty() || !points.getFirst().hasVectors()) {
      return Optional.empty();
    }
    var namedVectors = points.getFirst().getVectors().getVectors().getVectorsMap();
    if (!namedVectors.containsKey(QdrantPointMapper.DENSE_VECTOR)) {
      return Optional.empty();
    }
    List<Float> values =
        namedVectors.get(QdrantPointMapper.DENSE_VECTOR).getDense().getDataList();
    float[] vector = new float[values.size()];
    for (int index = 0; index < values.size(); index++) {
      vector[index] = values.get(index);
    }
    return Optional.of(vector);
  }

  /** Creates named dense and sparse vectors plus indexes used by payload filters. */
  public void ensureCollection() throws IOException {
    boolean exists = await(client.collectionExistsAsync(collection), "check Qdrant collection");
    if (exists) {
      return;
    }
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
        Collections.SparseVectorParams.newBuilder()
            .setModifier(Collections.Modifier.Idf)
            .build();
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
    createPayloadIndex("language", FieldType.FieldTypeKeyword);
    createPayloadIndex("durationMinutes", FieldType.FieldTypeInteger);
  }

  /** Executes a universal Qdrant query and maps its fused score to the shared candidate type. */
  private List<AudiobookCandidate> query(QueryPoints request) throws IOException {
    List<ScoredPoint> matches = await(client.queryAsync(request), "query Qdrant");
    return matches.stream()
        .map(point -> new AudiobookCandidate(mapper.toRecord(point), (double) point.getScore()))
        .toList();
  }

  /** Builds common collection, filter, limit, and payload settings for every query mode. */
  private QueryPoints.Builder baseQuery(AudiobookFilters filters, int limit) {
    QueryPoints.Builder request =
        QueryPoints.newBuilder()
            .setCollectionName(collection)
            .setLimit(limit)
            .setWithPayload(enable(true));
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
    if (filters.authors() != null && !filters.authors().isEmpty()) {
      builder.addMust(matchKeywords("authors", filters.authors()));
    }
    if (filters.narrators() != null && !filters.narrators().isEmpty()) {
      builder.addMust(matchKeywords("narrators", filters.narrators()));
    }
    if (filters.language() != null && !filters.language().isBlank()) {
      builder.addMust(matchKeyword("language", filters.language()));
    }
    if (filters.maximumDurationMinutes() != null) {
      builder.addMust(
          range(
              "durationMinutes",
              Range.newBuilder().setLte(filters.maximumDurationMinutes()).build()));
    }
    return builder.build();
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
