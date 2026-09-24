package com.gen3.recommenderagent.storage.audiobook;

import java.util.List;

/** Catalogue fields needed by search, candidate ranking, and embedding generation. */
public record AudiobookRecord(
    String id,
    String source,
    String title,
    List<String> authors,
    String description,
    List<String> narrators,
    String language,
    Integer durationMinutes) {

  /** Keeps existing callers compatible while metadata is progressively added to source records. */
  public AudiobookRecord(
      String id, String source, String title, List<String> authors, String description) {
    this(id, source, title, authors, description, List.of(), null, null);
  }
}
