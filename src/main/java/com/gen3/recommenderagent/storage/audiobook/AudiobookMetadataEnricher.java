package com.gen3.recommenderagent.storage.audiobook;

/** Adds temporary or externally sourced metadata to incomplete catalogue records. */
public interface AudiobookMetadataEnricher {

  /** Returns a record with missing searchable metadata populated. */
  AudiobookRecord enrich(AudiobookRecord record);
}
