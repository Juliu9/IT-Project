package com.gen3.snapshotjob;

import org.springframework.core.env.Environment;

/** Shared catalogue identity used to publish and reopen the same snapshot. */
public record SnapshotIdentity(String backend, String collection) {
  /** Creates a backend and collection identity, rejecting blank labels. */
  public SnapshotIdentity {
    if (backend == null || backend.isBlank() || collection == null || collection.isBlank()) {
      throw new IllegalArgumentException("Snapshot backend and collection must be configured");
    }
  }

  /** Resolves snapshot identity from the selected backend's configured collection. */
  public static SnapshotIdentity from(Environment environment) {
    String backend = environment.getProperty("audiobook.database", "qdrant");
    return new SnapshotIdentity(backend, environment.getRequiredProperty(backend + ".collection"));
  }
}
