package com.gen3.embeddingjob.store;

import static io.qdrant.client.WithPayloadSelectorFactory.enable;

import com.gen3.recommenderagent.storage.audiobook.model.StoredAudiobook;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantPointMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.ScrollPoints;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Qdrant catalogue pagination and vector-only writes at existing opaque point IDs. */
@Component
@ConditionalOnProperty(name = "audiobook.database", havingValue = "qdrant", matchIfMissing = true)
public class QdrantEmbeddingStore implements AudiobookEmbeddingStore {
  private final QdrantClient client;
  private final QdrantPointMapper mapper;
  private final String collection;
  private final int vectorDimension;
  private boolean checked;

  public QdrantEmbeddingStore(
      QdrantClient client,
      QdrantPointMapper mapper,
      @Value("${qdrant.collection:audiobooks_hybrid}") String collection,
      @Value("${qdrant.embedding-dimension:1536}") int vectorDimension) {
    this.client = client;
    this.mapper = mapper;
    this.collection = collection;
    this.vectorDimension = vectorDimension;
  }

  @Override
  public Page embeddingPage(String cursor, int limit) throws IOException {
    requireCollection();
    var request =
        ScrollPoints.newBuilder()
            .setCollectionName(collection)
            .setLimit(limit)
            .setWithPayload(enable(true));
    if (cursor != null) {
      request.setOffset(pointId(cursor));
    }
    var page = await(client.scrollAsync(request.build()), "read catalogue for embedding indexing");
    return new Page(
        page.getResultList().stream()
            .map(
                point ->
                    new StoredAudiobook(
                        mapper.toRecord(point),
                        Base64.getEncoder().encodeToString(point.getId().toByteArray())))
            .toList(),
        page.hasNextPageOffset()
            ? Base64.getEncoder().encodeToString(page.getNextPageOffset().toByteArray())
            : null);
  }

  @Override
  public void updateEmbeddings(List<Update> updates) throws IOException {
    if (updates.isEmpty()) {
      return;
    }
    requireCollection();
    var request =
        io.qdrant.client.grpc.Points.UpdatePointVectors.newBuilder()
            .setCollectionName(collection)
            .setWait(true);
    for (Update update : updates) {
      validateDimension(update.embedding().vector());
      request.addPoints(
          io.qdrant.client.grpc.Points.PointVectors.newBuilder()
              .setId(pointId(update.storageReference()))
              .setVectors(mapper.toPoint(update.embedding()).getVectors()));
    }
    await(client.updateVectorsAsync(request.build()), "update existing catalogue vectors");
  }

  private void requireCollection() throws IOException {
    if (!checked) {
      if (!await(client.collectionExistsAsync(collection), "check Qdrant collection")) {
        throw new IOException("Qdrant collection does not exist: " + collection);
      }
      checked = true;
    }
  }

  private void validateDimension(float[] vector) {
    if (vector == null || vector.length != vectorDimension) {
      throw new IllegalArgumentException("Embedding dimension mismatch");
    }
  }

  private io.qdrant.client.grpc.Common.PointId pointId(String reference) throws IOException {
    try {
      return io.qdrant.client.grpc.Common.PointId.parseFrom(Base64.getDecoder().decode(reference));
    } catch (IllegalArgumentException exception) {
      throw new IOException("Invalid Qdrant storage reference", exception);
    }
  }

  private <T> T await(Future<T> future, String operation) throws IOException {
    try {
      return future.get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted: " + operation, exception);
    } catch (ExecutionException exception) {
      throw new IOException("Failed: " + operation, exception.getCause());
    }
  }
}
