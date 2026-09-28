package com.gen3.recommenderagent.storage.audiobook.model;

/**
 * A database-independent audiobook search result.
 *
 * @param audiobook catalogue data used by the application and response pipeline
 * @param score similarity or relevance score supplied by the search database; may be null
 * @param embedding normalized dense vector returned with the candidate; may be null
 */
public record AudiobookCandidate(AudiobookRecord audiobook, Double score, float[] embedding) {

  /** Keeps repositories without stored vectors compatible with the shared candidate type. */
  public AudiobookCandidate(AudiobookRecord audiobook, Double score) {
    this(audiobook, score, null);
  }
}
