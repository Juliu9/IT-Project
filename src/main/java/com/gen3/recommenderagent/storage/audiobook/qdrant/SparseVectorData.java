package com.gen3.recommenderagent.storage.audiobook.qdrant;

import java.util.List;

/** Immutable sparse vector containing aligned token indices and term weights. */
public record SparseVectorData(List<Integer> indices, List<Float> values) {

  /** Reports whether the encoder found any searchable terms. */
  public boolean isEmpty() {
    return indices == null || indices.isEmpty();
  }
}
