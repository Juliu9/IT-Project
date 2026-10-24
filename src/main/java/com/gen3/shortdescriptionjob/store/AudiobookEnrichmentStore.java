package com.gen3.shortdescriptionjob.store;

import com.gen3.catalogue.model.StoredAudiobook;
import java.io.IOException;
import java.util.List;

/** Reads and safely enriches catalogue records without exposing storage-specific identifiers. */
public interface AudiobookEnrichmentStore {

  /** Stable backend and dataset identity; excludes credentials and opaque record references. */
  DatasetIdentity datasetIdentity();

  record DatasetIdentity(String backend, String dataset) {
    public DatasetIdentity {
      if (backend == null || backend.isBlank() || dataset == null || dataset.isBlank()) {
        throw new IllegalArgumentException("Backend and dataset identity must not be blank");
      }
    }
  }

  static boolean eligible(StoredAudiobook book) {
    return book != null
        && book.record() != null
        && book.storageReference() != null
        && !book.storageReference().isBlank()
        && book.record().id() != null
        && !book.record().id().isBlank()
        && book.record().description() != null
        && !book.record().description().isBlank();
  }

  /** The limit counts eligible records, not skipped catalogue rows. */
  List<StoredAudiobook> findRecordsWithoutShortDescription(int maximumRecords) throws IOException;

  void updateShortDescriptions(List<ShortDescriptionUpdate> updates) throws IOException;

  record ShortDescriptionUpdate(String storageReference, String description) {}
}
