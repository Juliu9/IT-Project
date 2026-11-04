package com.gen3.shortdescriptionjob.store;

import com.gen3.shared.audiobookcatalogue.model.StoredAudiobook;
import com.gen3.shortdescriptionjob.ShortDescriptionMode;
import java.io.IOException;
import java.util.List;

/** Reads and safely enriches catalogue records without exposing storage-specific identifiers. */
public interface AudiobookEnrichmentStore {

  /** Checks that a stored book has the fields required for summary generation. */
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
  List<StoredAudiobook> findRecordsForShortDescription(
      int maximumRecords, ShortDescriptionMode mode) throws IOException;

  /** Writes generated summaries to the selected records according to the update mode. */
  void updateShortDescriptions(List<ShortDescriptionUpdate> updates, ShortDescriptionMode mode)
      throws IOException;

  record ShortDescriptionUpdate(String storageReference, String description) {}
}
