package com.gen3.recommenderagent.storage.user.port;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.io.IOException;
import java.util.Optional;

public interface UserPreferenceVectorRepository {

  void save(UserPreferenceEmbedding embedding) throws IOException;

  Optional<UserPreferenceEmbedding> findEmbeddingByUserId(String userId) throws IOException;
}
