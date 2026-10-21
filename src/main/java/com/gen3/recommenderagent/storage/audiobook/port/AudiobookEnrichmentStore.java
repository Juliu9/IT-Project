package com.gen3.recommenderagent.storage.audiobook.port;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
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

  List<StoredAudiobook> findRecordsWithoutShortDescription(int maximumRecords) throws IOException;

  void updateShortDescriptions(List<ShortDescriptionUpdate> updates) throws IOException;

  record StoredAudiobook(AudiobookRecord record, String storageReference) {}

  record ShortDescriptionUpdate(String storageReference, String description) {}
}
