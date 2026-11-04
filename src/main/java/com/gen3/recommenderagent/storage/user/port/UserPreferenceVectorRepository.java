package com.gen3.recommenderagent.storage.user.port;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface UserPreferenceVectorRepository {

  /** Stores one user's supplied favourites and history vectors. */
  void save(UserPreferenceEmbedding embedding) throws IOException;

  /** Removes both stored taste vectors without deleting unrelated user metadata. */
  void clear(String userId) throws IOException;

  /** Clears stored taste vectors for each supplied user ID. */
  default void clearAll(List<String> userIds) throws IOException {
    for (String userId : userIds) {
      clear(userId);
    }
  }

  /** Saves the supplied user preference vectors in input order. */
  default void saveAll(List<UserPreferenceEmbedding> embeddings) throws IOException {
    for (var embedding : embeddings) {
      save(embedding);
    }
  }

  /** Updates supplied vector sides while retaining an existing side omitted by the import. */
  default void saveAllPreservingMissing(List<UserPreferenceEmbedding> embeddings)
      throws IOException {
    for (var embedding : embeddings) {
      var existing = findEmbeddingByUserId(embedding.userId()).orElse(null);
      float[] favourites =
          hasVector(embedding.favouritesVector())
              ? embedding.favouritesVector()
              : existing == null ? null : existing.favouritesVector();
      float[] history =
          hasVector(embedding.historyVector())
              ? embedding.historyVector()
              : existing == null ? null : existing.historyVector();
      save(new UserPreferenceEmbedding(embedding.userId(), favourites, history));
    }
  }

  /** Reports whether a vector is present and nonempty. */
  private static boolean hasVector(float[] vector) {
    return vector != null && vector.length > 0;
  }

  /** Loads the stored taste vectors for one user, returning empty when none are available. */
  Optional<UserPreferenceEmbedding> findEmbeddingByUserId(String userId) throws IOException;
}
