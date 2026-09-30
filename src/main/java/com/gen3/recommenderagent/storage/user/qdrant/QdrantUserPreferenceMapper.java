package com.gen3.recommenderagent.storage.user.qdrant;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class QdrantUserPreferenceMapper {

  public static final String FAVOURITES_VECTOR = "dense-user-favourites";
  public static final String HISTORY_VECTOR = "dense-user-history";

  public PointStruct toPoint(UserPreferenceEmbedding embedding) {
    Map<String, io.qdrant.client.grpc.Points.Vector> vectorMap = new HashMap<>();

    if (embedding.favouritesVector() != null && embedding.favouritesVector().length > 0) {
      vectorMap.put(FAVOURITES_VECTOR, vector(embedding.favouritesVector()));
    }
    if (embedding.historyVector() != null && embedding.historyVector().length > 0) {
      vectorMap.put(HISTORY_VECTOR, vector(embedding.historyVector()));
    }

    return PointStruct.newBuilder()
        .setId(pointId(embedding.userId()))
        .setVectors(namedVectors(vectorMap))
        .putPayload("userId", value(embedding.userId()))
        .build();
  }

  public io.qdrant.client.grpc.Common.PointId pointId(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("User ID must not be blank");
    }
    UUID uuid =
        UUID.nameUUIDFromBytes(("user-preference:" + userId).getBytes(StandardCharsets.UTF_8));
    return id(uuid);
  }

  public float[] toVector(RetrievedPoint point, String vectorName) {
    if (point == null || !point.hasVectors()) {
      return null;
    }

    var namedVectors = point.getVectors().getVectors().getVectorsMap();
    if (!namedVectors.containsKey(vectorName) || !namedVectors.get(vectorName).hasDense()) {
      return null;
    }

    var values = namedVectors.get(vectorName).getDense().getDataList();
    if (values.isEmpty()) {
      return null;
    }

    float[] vector = new float[values.size()];
    for (int index = 0; index < values.size(); index++) {
      vector[index] = values.get(index);
    }
    return vector;
  }
}
