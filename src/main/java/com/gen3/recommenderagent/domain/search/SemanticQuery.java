package com.gen3.recommenderagent.domain.search;

import java.util.List;

public class SemanticQuery {

  private List<String> topics;
  private List<String> genres;
  private List<String> authors;
  private List<String> narrators;

  /** Reports whether any criteria or preference values are present. */
  public boolean hasValues() {
    return java.util.stream.Stream.of(topics, genres, authors, narrators)
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .anyMatch(value -> value != null && !value.isBlank());
  }

  /** Creates an empty instance for subsequent configuration or state restoration. */
  public SemanticQuery() {}

  public List<String> getTopics() {
    return topics;
  }

  public void setTopics(List<String> topics) {
    this.topics = topics;
  }

  public List<String> getGenres() {
    return genres;
  }

  public void setGenres(List<String> genres) {
    this.genres = genres;
  }

  public List<String> getAuthors() {
    return authors;
  }

  public void setAuthors(List<String> authors) {
    this.authors = authors;
  }

  /** Returns narrator names used as semantic preferences. */
  public List<String> getNarrators() {
    return narrators;
  }

  /** Stores narrator names extracted from the user's request. */
  public void setNarrators(List<String> narrators) {
    this.narrators = narrators;
  }
}
