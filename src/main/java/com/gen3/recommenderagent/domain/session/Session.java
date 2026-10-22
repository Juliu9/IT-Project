package com.gen3.recommenderagent.domain.session;

import static com.gen3.recommenderagent.domain.RecommendationPolicy.MAX_BOOK_COUNT;
import static com.gen3.recommenderagent.domain.RecommendationPolicy.MAX_SHOWN_BOOKS;
import static com.gen3.recommenderagent.domain.RecommendationPolicy.RECOMMENDATION_BATCH_SIZE;
import static com.gen3.recommenderagent.domain.RecommendationPolicy.clampOrDefault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Session {

  private String sessionId;
  private String userId;

  private Instant createdAt;
  private Instant updatedAt;
  private Instant expiresAt;
  private long version;
  private Integer bookCount = MAX_BOOK_COUNT;
  private List<String> shownBooks;
  private List<SessionRequest> requests = new ArrayList<>();
  private SessionPreferences preferences;
  // shortcut: cached details are snapshots; validate the catalogue if live removals must take
  // effect.
  private List<Recommendation> pendingRecommendations = new ArrayList<>();

  public boolean isAwaitingPersonalisedPreferences() {
    return requests != null
        && requests.stream().anyMatch(SessionRequest::isPreferenceClarificationRequired);
  }

  public Session() {}

  public List<String> getShownBooks() {
    return shownBooks;
  }

  public void setShownBooks(List<String> shownBooks) {
    this.shownBooks = shownBooks == null ? null : new ArrayList<>(shownBooks);
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

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
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
    if (request == null) {
      return;
    }
    if (ConversationState.isSearchContext(request)) {
      requests.removeIf(ConversationState::isSearchContext);
    } else {
      requests.removeIf(existing -> !ConversationState.isSearchContext(existing));
    }
    this.requests.add(request);
    this.updatedAt = Instant.now();
  }

  public SessionPreferences getPreferences() {
    return preferences;
  }

  public void setPreferences(SessionPreferences preferences) {
    this.preferences = preferences;
  }

  public List<Recommendation> getPendingRecommendations() {
    return pendingRecommendations;
  }

  public void setPendingRecommendations(List<Recommendation> recommendations) {
    pendingRecommendations =
        recommendations == null
            ? new ArrayList<>()
            : new ArrayList<>(recommendations.stream().limit(RECOMMENDATION_BATCH_SIZE).toList());
  }

  public void addRecommendationIds(List<String> bookIds) {
    if (this.shownBooks == null) {
      this.shownBooks = new ArrayList<>();
    }
    if (bookIds != null) {
      bookIds.stream()
          .filter(bookId -> bookId != null && !bookId.isBlank())
          .filter(bookId -> !this.shownBooks.contains(bookId))
          .forEach(
              bookId -> {
                if (this.shownBooks.size() == MAX_SHOWN_BOOKS) {
                  this.shownBooks.removeFirst();
                }
                this.shownBooks.add(bookId);
              });
    }
  }

  public void clearHistory() {
    this.pendingRecommendations.clear();
    this.requests.clear();
    if (this.shownBooks != null) {
      this.shownBooks.clear();
    }
    this.updatedAt = Instant.now();
  }
}
