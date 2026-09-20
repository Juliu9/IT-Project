package com.gen3.recommenderagent.domain.session;

import java.util.ArrayList;
import java.util.List;

public class Recommendations {

  private List<Recommendation> recommendations = new ArrayList<>();
  private List<String> shownBooks = new ArrayList<>();
  private List<String> excludedBooks = new ArrayList<>();

  public Recommendations() {}

  public List<Recommendation> getRecommendations() {
    return recommendations;
  }

  public void setRecommendations(List<Recommendation> recommendations) {
    this.recommendations = recommendations;
  }

  public List<String> getShownBooks() {
    return shownBooks;
  }

  public void setShownBooks(List<String> shownBooks) {
    this.shownBooks = shownBooks;
  }

  public List<String> getExcludedBooks() {
    return excludedBooks;
  }

  public void setExcludedBooks(List<String> excludedBooks) {
    this.excludedBooks = excludedBooks;
  }
}
