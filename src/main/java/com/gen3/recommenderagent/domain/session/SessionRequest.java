package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.domain.Intent;
import java.time.Instant;
import java.util.List;

public class SessionRequest {

  private String requestId;
  private String rawText;
  private Instant createdAt;

  private Intent intent;

  private SemanticQuery positiveSemanticQuery;
  private SemanticQuery negativeSemanticQuery;

  private MustInclude mustInclude;
  private MustNotInclude mustNotInclude;
  private Integer bookCount;

  private List<Recommendation> recommendations;

  public SessionRequest() {}

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  public String getRawText() {
    return rawText;
  }

  public void setRawText(String rawText) {
    this.rawText = rawText;
  }

  public Intent getIntent() {
    return intent;
  }

  public void setIntent(Intent intent) {
    this.intent = intent;
  }

  public SemanticQuery getPositiveSemanticQuery() {
    return positiveSemanticQuery;
  }

  public void setPositiveSemanticQuery(SemanticQuery positiveSemanticQuery) {
    this.positiveSemanticQuery = positiveSemanticQuery;
  }

  public SemanticQuery getNegativeSemanticQuery() {
    return negativeSemanticQuery;
  }

  public void setNegativeSemanticQuery(SemanticQuery negativeSemanticQuery) {
    this.negativeSemanticQuery = negativeSemanticQuery;
  }

  public MustInclude getMustInclude() {
    return mustInclude;
  }

  public void setMustInclude(MustInclude mustInclude) {
    this.mustInclude = mustInclude;
  }

  public MustNotInclude getMustNotInclude() {
    return mustNotInclude;
  }

  public void setMustNotInclude(MustNotInclude mustNotInclude) {
    this.mustNotInclude = mustNotInclude;
  }

  public Integer getBookCount() {
    return bookCount;
  }

  public void setBookCount(Integer bookCount) {
    this.bookCount = bookCount;
  }

  public List<Recommendation> getRecommendations() {
    return recommendations;
  }

  public void setRecommendations(List<Recommendation> recommendations) {
    this.recommendations = recommendations;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
