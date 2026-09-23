package com.gen3.recommenderagent.storage.audiobook;

import java.io.IOException;
import java.util.List;

/**
 * Search boundary for audiobook records; implementations may use different
 * databases.
 */
public interface AudiobookRepository {

  /** Searches for audiobook records, returning at most {@code limit} matches. */
  AudiobookSearchPage searchBooks(String query, int limit) throws IOException;

  /**
   * Searches with the lexical query and an optional normalized semantic query
   * vector.
   */
  default AudiobookSearchPage searchBooks(String query, int limit, float[] queryVector)
      throws IOException {
    return searchBooks(query, limit);
  }

  /**
   * Stores an audiobook vector alongside its Solr document when supported by the
   * repository.
   */
  default void indexEmbedding(AudiobookRecord record, float[] vector) throws IOException {
  }

  /**
   * Loads a page of the complete audiobook catalogue for import-time indexing.
   */
  default List<AudiobookRecord> findAllBooks(int offset, int limit) throws IOException {
    return List.of();
  }

  /** Stores a batch of audiobook vectors with one persistence operation. */
  default void indexEmbeddings(List<AudiobookEmbedding> embeddings) throws IOException {
    for (AudiobookEmbedding embedding : embeddings) {
      indexEmbedding(embedding.record(), embedding.vector());
    }
  }

  record AudiobookEmbedding(AudiobookRecord record, float[] vector) {
  }
}
