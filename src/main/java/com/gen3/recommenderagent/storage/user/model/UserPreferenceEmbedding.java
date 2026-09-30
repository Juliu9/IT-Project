package com.gen3.recommenderagent.storage.user.model;

/**
 * Represents the aggregated preference vector for one user.
 *
 * @param userId external user identifier
 * @param favouritesVector normalized preference embedding
 * @param historyVector normalized preference embedding
 */
public record UserPreferenceEmbedding(
    String userId, float[] favouritesVector, float[] historyVector) {

  public UserPreferenceEmbedding {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("User ID must not be blank");
    }

    boolean hasFavs = favouritesVector != null && favouritesVector.length > 0;
    boolean hasHist = historyVector != null && historyVector.length > 0;

    if (!hasFavs && !hasHist) {
      throw new IllegalArgumentException("At least one preference vector must be provided");
    }
  }
}
