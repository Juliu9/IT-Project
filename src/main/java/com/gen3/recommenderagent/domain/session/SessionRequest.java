package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.domain.Intent;

import java.time.Instant;

public class SessionRequest {

    private String requestId;
    private String rawText;
    private Instant createdAt;

    // Used to get personalised recommendations
    private boolean isPersonalised;

    // IMPORTANT !!
    private Intent intent;

    private Query query;
    private Preferences preferences;
    private Constraints constraints;
    private Feedback feedback;

    private BookReference bookReference;

    private Recommendations recommendations;

    public SessionRequest() {
    }

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

    public boolean isPersonalised() {
        return isPersonalised;
    }

    public void setPersonalised(boolean personalised) {
        isPersonalised = personalised;
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

    public Feedback getFeedback() {
        return feedback;
    }

    public void setFeedback(Feedback feedback) {
        this.feedback = feedback;
    }

    public BookReference getBookReference() {
        return bookReference;
    }

    public void setBookReference(BookReference bookReference) {
        this.bookReference = bookReference;
    }

    public Recommendations getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(Recommendations recommendations) {
        this.recommendations = recommendations;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}