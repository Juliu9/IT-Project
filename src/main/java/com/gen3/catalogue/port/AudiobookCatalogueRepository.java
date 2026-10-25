package com.gen3.catalogue.port;

import com.gen3.catalogue.model.AudiobookRecord;
import java.io.IOException;
import java.util.List;

/** Provides paginated catalogue access without exposing vector-storage operations. */
public interface AudiobookCatalogueRepository {

  /** Loads a deterministic catalogue page for indexing or migration. */
  List<AudiobookRecord> findAllBooks(int offset, int limit) throws IOException;
}
