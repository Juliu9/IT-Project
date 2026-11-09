package com.gen3.recommenderagent.storage.user.model;

import java.time.Instant;

/** A user's search taste, built up from their past searches (MAPA-30). */
public record SearchHistoryProfile(
    String userId, float[] tasteVector, int searchCount, Instant updatedAt) {

  public SearchHistoryProfile {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("User ID must not be blank");
    }
    if (tasteVector == null || tasteVector.length == 0) {
      throw new IllegalArgumentException("Taste vector must not be empty");
    }
    if (searchCount < 1) {
      throw new IllegalArgumentException("Search count must be at least 1");
    }
    if (updatedAt == null) {
      throw new IllegalArgumentException("Updated time must not be null");
    }
  }
}
