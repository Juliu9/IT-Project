package com.gen3.recommenderagent.ranker;

import java.util.List;

/** Catalogue fields needed by search, candidate ranking, and embedding generation. */
public record AudiobookRecord(
    String id,
    String source,
    String title,
    List<String> authors,
    String description,
    Double score,
    List<String> genres,
    String narrator) {

  public AudiobookRecord(
      String id,
      String source,
      String title,
      List<String> authors,
      String description,
      Double score) {
    this(id, source, title, authors, description, score, List.of(), null);
  }
}
