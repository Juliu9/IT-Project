package com.gen3.recommenderagent.storage.audiobook;

import java.io.IOException;
import java.util.List;

/** Provides lexical catalogue access without exposing vector-storage operations. */
public interface AudiobookCatalogueRepository {

  /** Searches the catalogue and returns at most {@code limit} matching records. */
  AudiobookSearchPage searchBooks(String query, int limit) throws IOException;

  /** Loads a deterministic catalogue page for indexing or migration. */
  List<AudiobookRecord> findAllBooks(int offset, int limit) throws IOException;
}
