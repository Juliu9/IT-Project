package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies deterministic sparse lexical vector generation. */
class Bm25SparseTextEncoderTest {

  /** Repeated words receive a larger saturated weight without duplicating their sparse index. */
  @Test
  void weightsRepeatedTermsAndRemovesStopWords() {
    Bm25SparseTextEncoder encoder = new Bm25SparseTextEncoder();

    SparseVectorData once = encoder.encode("the dragon");
    SparseVectorData repeated = encoder.encode("dragon dragon dragon");

    assertThat(once.indices()).hasSize(1);
    assertThat(repeated.indices()).containsExactlyElementsOf(once.indices());
    assertThat(repeated.values().getFirst()).isGreaterThan(once.values().getFirst());
  }
}
