package com.gen3.recommenderagent.domain.session;

import static com.gen3.recommenderagent.domain.RecommendationPolicy.MAX_BOOK_COUNT;
import static com.gen3.recommenderagent.domain.RecommendationPolicy.MAX_SHOWN_BOOKS;
import static com.gen3.recommenderagent.domain.RecommendationPolicy.RECOMMENDATION_BATCH_SIZE;
import static com.gen3.recommenderagent.domain.RecommendationPolicy.clampOrDefault;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Session {

  // Only the active question owns unfinished context; abandoned questions cannot resume.
  private PendingQuestion pendingQuestion;

  public PendingQuestion getPendingQuestion() {
    return pendingQuestion;
  }

  public void setPendingQuestion(PendingQuestion value) {
    pendingQuestion = value;
  }

  @JsonIgnore
  public ConversationDecision getPendingDecision() {
    return pendingQuestion == null ? null : pendingQuestion.getDecision();
  }

  public void setPendingDecision(ConversationDecision value) {
    if (value == null) {
      pendingQuestion = null;
    } else if (pendingQuestion == null || !pendingQuestion.accepts(value)) {
      pendingQuestion = PendingQuestion.create(value);
    } else {
      pendingQuestion.setDecision(value);
    }
  }

  @JsonIgnore
  public ConversationTurn getPendingRefinementRequest() {
    return pendingQuestion instanceof PendingQuestion.Search question
        ? question.getRequest()
        : null;
  }

  public void setPendingRefinementRequest(ConversationTurn value) {
    if (value != null) {
      if (!(pendingQuestion instanceof PendingQuestion.Search)) {
        pendingQuestion = new PendingQuestion.Search();
      }
      ((PendingQuestion.Search) pendingQuestion).setRequest(value);
    } else if (pendingQuestion instanceof PendingQuestion.Search question) {
      question.setRequest(null);
    }
  }

  @JsonIgnore
  public PendingSimilarBook getPendingSimilarBook() {
    return pendingQuestion instanceof PendingQuestion.SimilarBook question
        ? new PendingSimilarBook(question.getRequest(), question.getDecision())
        : null;
  }

  public void setPendingSimilarBook(PendingSimilarBook value) {
    if (value != null) {
      var question = new PendingQuestion.SimilarBook();
      question.setDecision(value.decision());
      question.setRequest(value.request());
      pendingQuestion = question;
    } else if (pendingQuestion instanceof PendingQuestion.SimilarBook) {
      pendingQuestion = null;
    }
  }

  @JsonIgnore
  public SessionRequest getPendingCountRequest() {
    return pendingQuestion instanceof PendingQuestion.Count question ? question.getRequest() : null;
  }

  public void setPendingCountRequest(SessionRequest value) {
    if (value != null) {
      var question = new PendingQuestion.Count();
      question.setRequest(value);
      pendingQuestion = question;
    } else if (pendingQuestion instanceof PendingQuestion.Count question) {
      question.setRequest(null);
    }
  }

  @JsonIgnore
  public ExplanationSelection getPendingExplanation() {
    return pendingQuestion instanceof PendingQuestion.Explanation question
        ? question.getSelection()
        : null;
  }

  public void setPendingExplanation(ExplanationSelection value) {
    if (value != null) {
      var question = new PendingQuestion.Explanation();
      question.setSelection(value);
      pendingQuestion = question;
    } else if (pendingQuestion instanceof PendingQuestion.Explanation question) {
      question.setSelection(null);
    }
  }

  private String sessionId;
  private String userId;

  private Instant createdAt;
  private Instant updatedAt;
  private Instant expiresAt;
  private long version;

  @JsonIgnore
  public boolean isAwaitingPreferenceUpdate() {
    return getPendingDecision() == ConversationDecision.PREFERENCE_UPDATE_CLARIFICATION;
  }

  private Integer bookCount = MAX_BOOK_COUNT;
  private List<String> shownBooks;
  private List<ConversationTurn> requests = new ArrayList<>();
  private SessionPreferences preferences;
  // REPEAT needs the latest resolved action, separately from reusable search/preference history.
  private ConversationTurn lastRequest;
  private ConversationDecision lastDecision;

  public ConversationTurn getLastRequest() {
    return lastRequest;
  }

  public void setLastRequest(ConversationTurn request) {
    lastRequest = request;
  }

  public ConversationDecision getLastDecision() {
    return lastDecision;
  }

  public void setLastDecision(ConversationDecision decision) {
    lastDecision = decision;
  }

  // shortcut: cached details are snapshots; validate the catalogue if live removals must take
  // effect.
  private List<Recommendation> pendingRecommendations = new ArrayList<>();

  @JsonIgnore
  public boolean isAwaitingPersonalisedPreferences() {
    return getPendingDecision() == ConversationDecision.PREFERENCE_CLARIFICATION;
  }

  @JsonIgnore
  public ConversationTurn getPendingPreferenceRequest() {
    return pendingQuestion instanceof PendingQuestion.Preference question
        ? question.getRequest()
        : null;
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

  public List<ConversationTurn> getRequests() {
    return requests;
  }

  public void setRequests(List<ConversationTurn> requests) {
    if (requests == null) {
      this.requests = new ArrayList<>();
      return;
    }
    this.requests =
        new ArrayList<>(
            requests.stream()
                .filter(
                    turn ->
                        !turn.isPreferenceClarificationRequired()
                            && !turn.isSearchClarificationRequired())
                .toList());
  }

  public SessionPreferences getPreferences() {
    return preferences;
  }

  public void setPreferences(SessionPreferences preferences) {
    this.preferences = preferences;
  }

  /** Calculates a preference edit without changing the saved profile during resolution. */
  public SessionPreferences updatedPreferences(
      SessionPreferences additions, SessionPreferences removals, List<String> replacementFields) {
    var current = preferences == null ? new SessionPreferences() : preferences;
    return current.updated(additions, removals, replacementFields);
  }

  public void clearPreferenceQuestions() {
    if (pendingQuestion instanceof PendingQuestion.Preference) {
      pendingQuestion = null;
    }
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
    pendingQuestion = null;
    this.lastRequest = null;
    this.lastDecision = null;
    this.pendingRecommendations.clear();
    this.requests.clear();
    if (this.shownBooks != null) {
      this.shownBooks.clear();
    }
    this.updatedAt = Instant.now();
  }
}
