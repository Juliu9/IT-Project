package com.gen3.recommenderagent.application.recommendation;

import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.shared.readhistory.port.ReadHistoryRepository;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** One bounded history snapshot per new page; failures deliberately fail open, never truncate. */
@Service
public class ReadHistoryExclusions {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReadHistoryExclusions.class);
  private final Optional<ReadHistoryRepository> repository;
  private final int maximumBooks;

  public ReadHistoryExclusions(
      Optional<ReadHistoryRepository> repository,
      @Value("${read-history.maximum-books-per-user:10000}") int maximumBooks) {
    if (maximumBooks < 1 || maximumBooks > 1_000_000) {
      throw new IllegalArgumentException("History limit must be between 1 and 1000000");
    }
    this.repository = repository;
    this.maximumBooks = maximumBooks;
  }

  public Set<String> load(String userId, String requestId) {
    if (repository.isEmpty()) {
      return Set.of();
    }
    return RequestTelemetry.measure("historyLoadMs", () -> lookup(userId, requestId));
  }

  private Set<String> lookup(String userId, String requestId) {
    try {
      var ids = repository.orElseThrow().findReadBookIds(userId, maximumBooks + 1);
      if (ids.size() > maximumBooks) {
        LOGGER.warn(
            "read.history.exclusion.skipped requestId={} reason=HISTORY_LIMIT_EXCEEDED limit={}",
            requestId,
            maximumBooks);
        return Set.of();
      }
      return Set.copyOf(ids);
    } catch (IOException exception) {
      // Do not expose database URLs, credentials, or full reading histories through error logs.
      LOGGER.warn(
          "read.history.exclusion.skipped requestId={} reason=STORE_UNAVAILABLE", requestId);
      return Set.of();
    }
  }
}
