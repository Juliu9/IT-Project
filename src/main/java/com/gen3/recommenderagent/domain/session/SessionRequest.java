package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.domain.Intent;
import java.time.Instant;
import java.util.List;

public class SessionRequest {

  private String requestId;
  private String rawText;
  private Instant createdAt;
  private boolean personalised;

  // IMPORTANT !!
  private Intent intent;

  private Query query;
  private Preferences preferences;
  private Constraints constraints;
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

  public Query getQuery() {
    return query;
  }

  public void setQuery(Query query) {
    this.query = query;
  }

  public Preferences getPreferences() {
    return preferences;
  }

  public void setPreferences(Preferences preferences) {
    this.preferences = preferences;
  }

  public Constraints getConstraints() {
    return constraints;
  }

  public void setConstraints(Constraints constraints) {
    this.constraints = constraints;
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

  public boolean isPersonalised() {
    return personalised;
  }

  public void setPersonalised(boolean personalised) {
    this.personalised = personalised;
  }
}
