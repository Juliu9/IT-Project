package com.gen3.recommenderagent.storage.audiobook;

import java.io.IOException;
import java.util.List;

/** Initializes a vector store and writes audiobook embeddings to it. */
public interface AudiobookVectorIndexer {

  /** Prepares the backing vector store before an import begins. */
  default void initialize() throws IOException {}

  /** Stores one audiobook vector. */
  void indexEmbedding(AudiobookRecord record, float[] vector) throws IOException;

  /** Stores a batch of audiobook vectors. */
  default void indexEmbeddings(List<AudiobookEmbedding> embeddings) throws IOException {
    for (AudiobookEmbedding embedding : embeddings) {
      indexEmbedding(embedding.record(), embedding.vector());
    }
  }
}
