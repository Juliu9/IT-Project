package com.gen3.recommenderagent.storage.audiobook;

import java.io.IOException;
import java.util.Optional;

/** Stores and retrieves audiobook vectors independently from catalogue search. */
public interface AudiobookVectorRepository extends AudiobookVectorIndexer {

  /** Returns the dense vector stored for one catalogue book ID. */
  Optional<float[]> findEmbeddingByBookId(String bookId) throws IOException;
}
