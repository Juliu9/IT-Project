package com.gen3.snapshotjob;

import com.gen3.shared.audiobookcatalogue.snapshot.BookVectorSnapshot;

/** A selected catalogue's identity and read-only vector scan. */
public interface CatalogueSnapshotSource extends BookVectorSnapshot.Source {
  /** Returns the backend identifier used to match a snapshot source to its configuration. */
  String backend();

  /** Returns the collection identifier used to match a snapshot source to its configuration. */
  String collection();
}
