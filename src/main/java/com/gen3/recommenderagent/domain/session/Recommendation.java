package com.gen3.recommenderagent.domain.session;

public class Recommendation {

  private String bookId;
  private Integer rank;
  private Double score;

  public Recommendation() {}

  public Recommendation(String bookId, Integer rank, Double score) {
    this.bookId = bookId;
    this.rank = rank;
    this.score = score;
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
}
