package com.gen3.recommenderagent.domain.recommendation;

import java.util.List;

public class Recommendation {

  private String bookId;
  private Integer rank;
  private Double score;
  private String title;
  private String shortDescription;
  private Integer durationMinutes;
  private Integer publicationYear;

  public Integer getPublicationYear() {
    return publicationYear;
  }

  public void setPublicationYear(Integer value) {
    publicationYear = value;
  }

  public Integer getDurationMinutes() {
    return durationMinutes;
  }

  public void setDurationMinutes(Integer value) {
    durationMinutes = value;
  }

  // Retained with each queued book so later explanations use the evidence from its retrieval.
  private boolean readingPreferencesUsed;

  public boolean isReadingPreferencesUsed() {
    return readingPreferencesUsed;
  }

  public void setReadingPreferencesUsed(boolean used) {
    readingPreferencesUsed = used;
  }

  /** Required to read recommendations retained in Redis session JSON. */
  public Recommendation() {}

  public Recommendation(String bookId, Integer rank, Double score, String title) {
    this(bookId, rank, score, title, null);
  }

  public Recommendation(
      String bookId, Integer rank, Double score, String title, String shortDescription) {
    this.bookId = bookId;
    this.rank = rank;
    this.score = score;
    this.title = title;
    this.shortDescription = shortDescription;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getShortDescription() {
    return shortDescription;
  }

  public void setShortDescription(String shortDescription) {
    this.shortDescription = shortDescription;
  }

  public String getBookId() {
    return bookId;
  }

  public void setBookId(String bookId) {
    this.bookId = bookId;
  }

  public Integer getRank() {
    return rank;
  }

  public void setRank(Integer rank) {
    this.rank = rank;
  }

  public Double getScore() {
    return score;
  }

  public void setScore(Double score) {
    this.score = score;
  }

  public static List<String> bookIds(List<Recommendation> recommendations) {
    return recommendations.stream()
        .filter(recommendation -> recommendation != null)
        .map(Recommendation::getBookId)
        .filter(bookId -> bookId != null && !bookId.isBlank())
        .toList();
  }
}
