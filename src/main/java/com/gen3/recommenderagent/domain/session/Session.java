package com.gen3.recommenderagent.domain.session;

import static com.gen3.recommenderagent.common.ApplicationConstants.MAX_BOOK_COUNT;
import static com.gen3.recommenderagent.common.BookCountPolicy.clampOrDefault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Session {

  private String sessionId;
  private String userId;

  private Instant createdAt;
  private Instant updatedAt;
  private Instant expiresAt;
  private Integer bookCount = MAX_BOOK_COUNT;
  private List<String> shownBooks;
  private List<SessionRequest> requests = new ArrayList<>();

  public Session() {}

  public List<String> getShownBooks() {
    return shownBooks;
  }

  public void setShownBooks(List<String> shownBooks) {
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

  public Integer getBookCount() {
    return bookCount;
  }

  public void setBookCount(Integer bookCount) {
    this.bookCount = clampOrDefault(bookCount);
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

  public void addRecommendationIds(List<String> bookIds) {
    if (this.shownBooks == null) {
      this.shownBooks = new ArrayList<>();
    }
    if (bookIds != null) {
      bookIds.stream()
          .filter(bookId -> bookId != null && !bookId.isBlank())
          .filter(bookId -> !this.shownBooks.contains(bookId))
          .forEach(this.shownBooks::add);
    }
  }

  public void clearHistory() {
    this.requests.clear();
    if (this.shownBooks != null) {
      this.shownBooks.clear();
    }
    this.updatedAt = Instant.now();
  }
}
