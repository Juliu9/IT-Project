package com.gen3.snapshotjob;

import com.gen3.shared.audiobookcatalogue.snapshot.BookVectorSnapshot;

/** A selected catalogue's identity and read-only vector scan. */
public interface CatalogueSnapshotSource extends BookVectorSnapshot.Source {
  String backend();

  String collection();
}
