package com.gen3.recommenderagent.domain.session;

import static com.gen3.recommenderagent.domain.recommendation.RecommendationPolicy.MAX_BOOK_COUNT;
import static com.gen3.recommenderagent.domain.recommendation.RecommendationPolicy.clampOrDefault;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.conversation.ExplanationSelection;
import java.time.Instant;
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
  private SessionPreferences preferences;
  private ActiveSearch activeSearch = new ActiveSearch();
  private LastOutcome lastOutcome;

  public ActiveSearch getActiveSearch() {
    return activeSearch;
  }

  public void setActiveSearch(ActiveSearch value) {
    activeSearch = value == null ? new ActiveSearch() : value;
  }

  public LastOutcome getLastOutcome() {
    return lastOutcome;
  }

  public void setLastOutcome(LastOutcome value) {
    lastOutcome = value;
  }

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

  public void clearHistory() {
    pendingQuestion = null;
    this.lastOutcome = null;
    this.activeSearch = new ActiveSearch();
    this.updatedAt = Instant.now();
  }
}
