package com.gen3.recommenderagent.storage.user.port;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface UserPreferenceVectorRepository {

  void save(UserPreferenceEmbedding embedding) throws IOException;

  /** Removes both stored taste vectors without deleting unrelated user metadata. */
  void clear(String userId) throws IOException;

  default void clearAll(List<String> userIds) throws IOException {
    for (String userId : userIds) {
      clear(userId);
    }
  }

  default void saveAll(List<UserPreferenceEmbedding> embeddings) throws IOException {
    for (var embedding : embeddings) {
      save(embedding);
    }
  }

  Optional<UserPreferenceEmbedding> findEmbeddingByUserId(String userId) throws IOException;
}
