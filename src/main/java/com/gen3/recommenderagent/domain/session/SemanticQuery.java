package com.gen3.recommenderagent.domain.session;

import java.util.List;

public class SemanticQuery {

  private List<String> topics;
  private List<String> genres;
  private List<String> authors;
  private List<String> narrators;
  private List<String> keywords;

  public boolean hasValues() {
    return java.util.stream.Stream.of(topics, genres, authors, narrators, keywords)
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .anyMatch(value -> value != null && !value.isBlank());
  }

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

  public List<String> getKeywords() {
    return keywords;
  }

  public void setKeywords(List<String> keywords) {
    this.keywords = keywords;
  }
}
