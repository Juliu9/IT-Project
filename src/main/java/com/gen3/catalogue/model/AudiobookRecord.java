package com.gen3.catalogue.model;

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
    @com.fasterxml.jackson.annotation.JsonProperty("duration_minutes") Integer durationMinutes,
    String pubdate,
    String shortDescription) {

  public AudiobookRecord {
    pubdate = PublicationDate.normalize(pubdate);
  }

  /** Derived for year-based policy and ranking, never a stored catalogue field. */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public Integer publicationYear() {
    return PublicationDate.year(pubdate);
  }

  /** Catalogue metadata without a generated short description. */
  public AudiobookRecord(
      String id,
      String source,
      String title,
      List<String> authors,
      String description,
      List<String> narrators,
      String language,
      Integer durationMinutes,
      String pubdate) {
    this(
        id,
        source,
        title,
        authors,
        description,
        narrators,
        language,
        durationMinutes,
        pubdate,
        null);
  }

  /** Catalogue metadata without a known publication date or short description. */
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

  /** Minimal catalogue text record. */
  public AudiobookRecord(
      String id, String source, String title, List<String> authors, String description) {
    this(id, source, title, authors, description, List.of(), null, null, null, null);
  }
}
