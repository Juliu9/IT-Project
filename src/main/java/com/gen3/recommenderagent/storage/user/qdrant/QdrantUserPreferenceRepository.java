package com.gen3.recommenderagent.storage.user.qdrant;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class QdrantUserPreferenceRepository implements UserPreferenceVectorRepository {

    private final QdrantClient client;
    private final QdrantUserPreferenceMapper mapper;
    private final String collection;
    private final int vectorDimension;

    private final Object initializationLock = new Object();
    private volatile boolean initialized;

    public QdrantUserPreferenceRepository(
            QdrantClient client,
            QdrantUserPreferenceMapper mapper,
            @Value("${qdrant.user-preference-collection:user}") String collection,
            @Value("${qdrant.embedding-dimension:1536}") int vectorDimension) {
        this.client = client;
        this.mapper = mapper;
        this.collection = collection;
        this.vectorDimension = vectorDimension;
    }

    @Override
    public void save(UserPreferenceEmbedding embedding) throws IOException {
        if (embedding == null) return;
        initialize();

        if (hasVector(embedding.favouritesVector())) validateDimension(embedding.favouritesVector());
        if (hasVector(embedding.historyVector())) validateDimension(embedding.historyVector());

        await(client.upsertAsync(collection, List.of(mapper.toPoint(embedding))),
                "write user preference vectors to Qdrant");
    }

    @Override
    public Optional<UserPreferenceEmbedding> findEmbeddingByUserId(String userId) throws IOException {
        if (userId == null || userId.isBlank()) return Optional.empty();
        initialize();

        List<RetrievedPoint> points = await(
                client.retrieveAsync(collection, List.of(mapper.pointId(userId)), false, true, null),
                "retrieve a user preference vector from Qdrant");

        if (points.isEmpty()) return Optional.empty();

        RetrievedPoint point = points.getFirst();
        float[] favs = mapper.toVector(point, QdrantUserPreferenceMapper.FAVOURITES_VECTOR);
        float[] hist = mapper.toVector(point, QdrantUserPreferenceMapper.HISTORY_VECTOR);

        if (!hasVector(favs) && !hasVector(hist)) return Optional.empty();

        return Optional.of(new UserPreferenceEmbedding(userId, favs, hist));
    }

    public void initialize() throws IOException {
        if (initialized) return;
        synchronized (initializationLock) {
            if (initialized) return;
            boolean exists = await(client.collectionExistsAsync(collection), "check Qdrant user preference collection");
            if (!exists) createCollection();
            initialized = true;
        }
    }

    private void createCollection() throws IOException {
        Collections.VectorParams vectorParams = Collections.VectorParams.newBuilder()
                .setSize(vectorDimension)
                .setDistance(Collections.Distance.Dot)
                .build();

        Collections.VectorsConfig vectorsConfig = Collections.VectorsConfig.newBuilder()
                .setParamsMap(Collections.VectorParamsMap.newBuilder()
                        .putMap(QdrantUserPreferenceMapper.FAVOURITES_VECTOR, vectorParams)
                        .putMap(QdrantUserPreferenceMapper.HISTORY_VECTOR, vectorParams)
                        .build())
                .build();

        Collections.CreateCollection request = Collections.CreateCollection.newBuilder()
                .setCollectionName(collection)
                .setVectorsConfig(vectorsConfig)
                .build();

        await(client.createCollectionAsync(request), "create Qdrant user preference collection");
    }

    private void validateDimension(float[] vector) {
        if (vector.length != vectorDimension) {
            throw new IllegalArgumentException("Embedding dimension mismatch");
        }
    }

    private boolean hasVector(float[] vector) {
        return vector != null && vector.length > 0;
    }

    private <T> T await(java.util.concurrent.Future<T> future, String operation) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted: " + operation, e);
        } catch (ExecutionException e) {
            throw new IOException("Failed: " + operation, e.getCause());
        }
    }
}