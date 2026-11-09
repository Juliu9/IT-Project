package com.gen3.recommenderagent.application.recommendation;

import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.storage.user.model.SearchHistoryProfile;
import com.gen3.recommenderagent.storage.user.port.SearchHistoryRepository;
import com.gen3.shared.vector.VectorMath;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Loads and updates each user's search taste, failing open like read history (MAPA-30). */
@Service
public class SearchHistoryPreferences {
  private static final Logger LOGGER = LoggerFactory.getLogger(SearchHistoryPreferences.class);
  private final Optional<SearchHistoryRepository> repository;
  private final double newSearchShare;
  private final int minimumSearches;

  public SearchHistoryPreferences(
      Optional<SearchHistoryRepository> repository,
      @Value("${search-history.new-search-share:0.2}") double newSearchShare,
      @Value("${search-history.minimum-searches:3}") int minimumSearches) {
    if (!(newSearchShare > 0 && newSearchShare <= 1)) {
      throw new IllegalArgumentException("New search share must be above 0 and at most 1");
    }
    if (minimumSearches < 1) {
      throw new IllegalArgumentException("Minimum searches must be at least 1");
    }
    this.repository = repository;
    this.newSearchShare = newSearchShare;
    this.minimumSearches = minimumSearches;
  }

  boolean isEnabled() {
    return repository.isPresent();
  }

  public Optional<float[]> load(String userId, String requestId) {
    if (repository.isEmpty() || userId == null || userId.isBlank()) {
      return Optional.empty();
    }
    return RequestTelemetry.measure("searchHistoryLoadMs", () -> lookup(userId, requestId));
  }

  public void record(String userId, String requestId, float[] searchVector) {
    if (repository.isEmpty() || userId == null || userId.isBlank() || searchVector == null) {
      return;
    }
    try {
      float[] search = VectorMath.normalize(searchVector);
      var store = repository.orElseThrow();
      // A different vector size means the embedding model changed, so start the taste again.
      var previous =
          store
              .findByUserId(userId)
              .filter(profile -> profile.tasteVector().length == search.length);
      store.save(
          previous
              .map(profile -> blend(profile, search))
              .orElseGet(() -> new SearchHistoryProfile(userId, search, 1, Instant.now())));
    } catch (IOException exception) {
      LOGGER.warn("search.history.record.skipped requestId={} reason=STORE_UNAVAILABLE", requestId);
    } catch (IllegalArgumentException exception) {
      LOGGER.warn("search.history.record.skipped requestId={} reason=INVALID_VECTOR", requestId);
    }
  }

  private Optional<float[]> lookup(String userId, String requestId) {
    try {
      return repository
          .orElseThrow()
          .findByUserId(userId)
          .filter(profile -> profile.searchCount() >= minimumSearches)
          .map(SearchHistoryProfile::tasteVector);
    } catch (IOException exception) {
      LOGGER.warn("search.history.load.skipped requestId={} reason=STORE_UNAVAILABLE", requestId);
      return Optional.empty();
    }
  }

  // Each new search takes its share of the taste, so older searches slowly fade out.
  private SearchHistoryProfile blend(SearchHistoryProfile previous, float[] search) {
    float[] taste =
        VectorMath.weightedAverage(
            List.of(previous.tasteVector(), search), List.of(1 - newSearchShare, newSearchShare));
    return new SearchHistoryProfile(
        previous.userId(), taste, previous.searchCount() + 1, Instant.now());
  }
}
