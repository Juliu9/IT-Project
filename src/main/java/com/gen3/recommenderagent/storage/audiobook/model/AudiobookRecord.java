package com.gen3.recommenderagent.storage.audiobook.model;

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
    Integer durationMinutes,
    Integer publicationYear,
    String shortDescription) {

  /**
   * Keeps callers using the catalogue fields from before short descriptions were added compatible.
   */
  public AudiobookRecord(
      String id,
      String source,
      String title,
      List<String> authors,
      String description,
      List<String> narrators,
      String language,
      Integer durationMinutes,
      Integer publicationYear) {
    this(
        id,
        source,
        title,
        authors,
        description,
        narrators,
        language,
        durationMinutes,
        publicationYear,
        null);
  }

  /** Keeps callers using the metadata fields from before publication year was added compatible. */
  public AudiobookRecord(
      String id,
      String source,
      String title,
      List<String> authors,
      String description,
      List<String> narrators,
      String language,
      Integer durationMinutes) {
    this(id, source, title, authors, description, narrators, language, durationMinutes, null, null);
  }

  /** Keeps existing callers compatible while metadata is progressively added to source records. */
  public AudiobookRecord(
      String id, String source, String title, List<String> authors, String description) {
    this(id, source, title, authors, description, List.of(), null, null, null, null);
  }
}
