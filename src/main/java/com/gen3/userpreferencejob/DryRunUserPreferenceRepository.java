package com.gen3.userpreferencejob;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import com.gen3.shared.vector.VectorMath;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Import-only sink with no database dependency: validates output without saving or clearing it. */
@Component
@Primary
@ConditionalOnProperty(
    name = "user.preferences.import.mode",
    havingValue = "dry-run",
    matchIfMissing = true)
public class DryRunUserPreferenceRepository implements UserPreferenceVectorRepository {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(DryRunUserPreferenceRepository.class);
  private final int dimension;

  /** Configures the vector dimensions accepted by the non-writing import validator. */
  public DryRunUserPreferenceRepository(@Value("${embedding.dimensions:1536}") int dimension) {
    this.dimension = dimension;
  }

  /** Validates one proposed profile without persisting it. */
  @Override
  public void save(UserPreferenceEmbedding embedding) {
    saveAll(List.of(embedding));
  }

  /** Validates proposed profile vectors and logs their count without writing them. */
  @Override
  public void saveAll(List<UserPreferenceEmbedding> embeddings) {
    for (var embedding : embeddings) {
      validate(embedding.favouritesVector());
      validate(embedding.historyVector());
    }
    LOGGER.info("user.preferences.dry_run generatedProfiles={} writes=0", embeddings.size());
  }

  /** Rejects nonfinite, zero, or incorrectly sized vectors during dry-run validation. */
  private void validate(float[] vector) {
    if (vector == null || vector.length == 0) {
      return;
    }
    if (vector.length != dimension) {
      throw new IllegalArgumentException(
          "User vector dimension does not match embedding.dimensions");
    }
    VectorMath.normalize(vector);
  }

  /** Logs the proposed clear without changing a stored profile. */
  @Override
  public void clear(String userId) {
    clearAll(List.of(userId));
  }

  /** Logs the number of proposed clears without changing stored profiles. */
  @Override
  public void clearAll(List<String> userIds) {
    LOGGER.info("user.preferences.dry_run missingVectorUsers={} clears=0", userIds.size());
  }

  /** Rejects stored-profile reads because this sink only validates generated import output. */
  @Override
  public Optional<UserPreferenceEmbedding> findEmbeddingByUserId(String userId) {
    throw new UnsupportedOperationException("Dry-run imports do not read saved user profiles");
  }
}
