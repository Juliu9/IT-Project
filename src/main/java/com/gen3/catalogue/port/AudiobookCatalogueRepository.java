package com.gen3.catalogue.port;

import com.gen3.catalogue.model.AudiobookRecord;
import java.io.IOException;
import java.util.List;

/** Provides paginated catalogue access without exposing vector-storage operations. */
public interface AudiobookCatalogueRepository {

  /** Loads records after the last processed ID (null starts the scan) for indexing or migration. */
  List<AudiobookRecord> findAllBooks(String afterId, int limit) throws IOException;
}
