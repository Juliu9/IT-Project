package com.gen3.recommenderagent.storage.audiobook.port;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;

/** Adds temporary or externally sourced metadata to incomplete catalogue records. */
public interface AudiobookMetadataEnricher {

  /** Returns a record with missing searchable metadata populated. */
  AudiobookRecord enrich(AudiobookRecord record);
}
