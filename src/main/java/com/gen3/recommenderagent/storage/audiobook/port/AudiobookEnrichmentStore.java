package com.gen3.recommenderagent.storage.audiobook.port;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.io.IOException;
import java.util.List;

/** Reads and safely enriches catalogue records without exposing storage-specific identifiers. */
public interface AudiobookEnrichmentStore {

  List<StoredAudiobook> findRecordsWithoutShortDescription(int maximumRecords) throws IOException;

  void updateShortDescriptions(List<ShortDescriptionUpdate> updates) throws IOException;

  int enrichMissingMetadata(AudiobookMetadataEnricher metadataEnricher) throws IOException;

  record StoredAudiobook(AudiobookRecord record, String storageReference) {}

  record ShortDescriptionUpdate(String storageReference, String description) {}
}
