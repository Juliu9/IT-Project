package com.gen3.recommenderagent.domain.session;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.gen3.recommenderagent.domain.Intent;
import java.time.Instant;
import java.util.List;

public class SessionRequest {

  private String requestId;
  private String rawText;
  private Instant createdAt;

  private Intent intent;

  private SemanticQuery PositiveSemanticQuery;
  private SemanticQuery NegativeSemanticQuery;

  @JsonAlias("constraints")
  private Filter filter;

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
        return PositiveSemanticQuery;
    }

    public void setPositiveSemanticQuery(SemanticQuery positiveSemanticQuery) {
        PositiveSemanticQuery = positiveSemanticQuery;
    }

    public SemanticQuery getNegativeSemanticQuery() {
        return NegativeSemanticQuery;
    }

    public void setNegativeSemanticQuery(SemanticQuery negativeSemanticQuery) {
        NegativeSemanticQuery = negativeSemanticQuery;
    }

    public Filter getFilter() {
    return filter;
  }

  public void setFilter(Filter filter) {
    this.filter = filter;
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
