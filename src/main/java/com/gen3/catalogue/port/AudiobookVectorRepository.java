package com.gen3.catalogue.port;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Retrieves audiobook vectors independently from catalogue search. */
public interface AudiobookVectorRepository {

  /** Returns the dense vector stored for one catalogue book ID. */
  Optional<float[]> findEmbeddingByBookId(String bookId) throws IOException;

  /** Returns the available dense vectors for a bounded set of catalogue book IDs. */
  Map<String, float[]> findEmbeddingsByBookIds(Collection<String> bookIds) throws IOException;
}
