package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static io.qdrant.client.WithPayloadSelectorFactory.enable;

import com.gen3.recommenderagent.embedding.VectorMath;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRepository;
import com.gen3.recommenderagent.storage.audiobook.AudiobookSearchPage;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import java.io.IOException;
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
  private final EmbeddingModel embeddingModel;
  private final String collection;
  private final int vectorDimension;

  /** Creates a repository for the configured Qdrant collection and embedding size. */
  public QdrantAudiobookRepository(
      QdrantClient client,
      QdrantPointMapper mapper,
      EmbeddingModel embeddingModel,
      @Value("${qdrant.collection:audiobooks}") String collection,
      @Value("${qdrant.embedding-dimension:1536}") int vectorDimension) {
    this.client = client;
    this.mapper = mapper;
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
    if (queryVector == null || queryVector.length == 0 || limit <= 0) {
      return List.of();
    }
    validateDimension(queryVector);
    ensureCollection();

    SearchPoints request =
        SearchPoints.newBuilder()
            .setCollectionName(collection)
            .addAllVector(floatList(queryVector))
            .setLimit(limit)
            .setWithPayload(enable(true))
            .build();
    List<ScoredPoint> matches = await(client.searchAsync(request), "search Qdrant");
    return matches.stream()
        .map(point -> new AudiobookCandidate(mapper.toRecord(point), (double) point.getScore()))
        .toList();
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
    List<Float> values = points.getFirst().getVectors().getVector().getDense().getDataList();
    float[] vector = new float[values.size()];
    for (int index = 0; index < values.size(); index++) {
      vector[index] = values.get(index);
    }
    return Optional.of(vector);
  }

  /** Creates the collection with dot-product distance if it does not already exist. */
  public void ensureCollection() throws IOException {
    boolean exists = await(client.collectionExistsAsync(collection), "check Qdrant collection");
    if (exists) {
      return;
    }
    Collections.VectorParams vectorParams =
        Collections.VectorParams.newBuilder()
            .setSize(vectorDimension)
            .setDistance(Collections.Distance.Dot)
            .build();
    await(client.createCollectionAsync(collection, vectorParams), "create Qdrant collection");
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
