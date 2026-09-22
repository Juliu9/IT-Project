package com.gen3.recommenderagent.domain.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Session {

    private String sessionId;
    private String userId;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;
    private List<Recommendation> shownBooks;

    private List<SessionRequest> requests = new ArrayList<>();

    public Session() {
    }

    public List<Recommendation> getShownBooks() {
        return shownBooks;
    }

    public void setShownBooks(List<Recommendation> shownBooks) {
        this.shownBooks = shownBooks;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public List<SessionRequest> getRequests() {
        return requests;
    }

    public void setRequests(List<SessionRequest> requests) {
        this.requests = requests;
    }

    public void addRequest(SessionRequest request) {
        this.requests.add(request);
        this.updatedAt = Instant.now();
    }
    public void addShownBooks(List<Recommendation> books) {
        if (this.shownBooks == null) {
            this.shownBooks = new ArrayList<>();
        }
        this.shownBooks.addAll(books);
    }
}