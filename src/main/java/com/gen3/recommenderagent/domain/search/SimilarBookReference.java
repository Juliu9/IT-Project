package com.gen3.recommenderagent.domain.search;

import java.util.List;

/** The catalogue book and original vector anchoring one similarity search. */
public record SimilarBookReference(
    @com.fasterxml.jackson.annotation.JsonProperty("id") String bookId,
    String title,
    List<String> authors,
    float[] vector) {
  public SimilarBookReference {
    authors = authors == null ? List.of() : List.copyOf(authors);
    vector = vector == null ? null : vector.clone();
  }

  @Override
  public float[] vector() {
    return vector == null ? null : vector.clone();
  }
}
