package com.gen3.recommenderagent.domain.session;

import java.util.List;

public class Query {

  private List<String> topics;
  private List<String> genres;
  private List<String> authors;
  private List<String> narrators;
  private List<String> keywords;
  private List<String> positive;
  private List<String> negative;

  public Query() {}

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

  /** Returns narrator names that should become exact catalogue filters. */
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

  /** Returns concepts that should move semantic retrieval closer. */
  public List<String> getPositive() {
    return positive;
  }

  public void setPositive(List<String> positive) {
    this.positive = positive;
  }

  /** Returns concepts that should move semantic retrieval away. */
  public List<String> getNegative() {
    return negative;
  }

  public void setNegative(List<String> negative) {
    this.negative = negative;
  }
}
