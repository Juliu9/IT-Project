package com.gen3.recommenderagent.storage.audiobook.qdrant;

/** Converts catalogue or request text into a sparse lexical vector. */
public interface SparseTextEncoder {

  /** Produces deterministic token indices and BM25-style term-frequency weights. */
  SparseVectorData encode(String text);
}
