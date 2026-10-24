package com.gen3.recommenderagent.domain.session;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.time.Instant;
import java.util.List;

public class SessionRequest {

  @JsonIgnore private ConversationDecision decision;

  @JsonIgnore
  public ConversationDecision getDecision() {
    return decision;
  }

  public void setDecision(ConversationDecision decision) {
    this.decision = decision;
  }

  private String requestId;
  private String rawText;
  private Instant createdAt;

  private Integer referenceRank;
  private String referenceTitle;

  public Integer getReferenceRank() {
    return referenceRank;
  }

  public void setReferenceRank(Integer referenceRank) {
    this.referenceRank = referenceRank;
  }

  public String getReferenceTitle() {
    return referenceTitle;
  }

  public void setReferenceTitle(String referenceTitle) {
    this.referenceTitle = referenceTitle;
  }

  private boolean addAuthors;
  private boolean addNarrators;

  public boolean isAddAuthors() {
    return addAuthors;
  }

  public void setAddAuthors(boolean value) {
    addAuthors = value;
  }

  public boolean isAddNarrators() {
    return addNarrators;
  }

  public void setAddNarrators(boolean value) {
    addNarrators = value;
  }

  private SessionPreferences preferenceUpdates;
  private SessionPreferences preferenceRemovals;
  private List<String> replacePreferenceFields;
  private boolean explicitSearch;

  public SessionPreferences getPreferenceUpdates() {
    return preferenceUpdates;
  }

  public void setPreferenceUpdates(SessionPreferences value) {
    preferenceUpdates = value;
  }

  public SessionPreferences getPreferenceRemovals() {
    return preferenceRemovals;
  }

  public void setPreferenceRemovals(SessionPreferences value) {
    preferenceRemovals = value;
  }

  public List<String> getReplacePreferenceFields() {
    return replacePreferenceFields;
  }

  public void setReplacePreferenceFields(List<String> value) {
    replacePreferenceFields = value;
  }

  public boolean isExplicitSearch() {
    return explicitSearch;
  }

  public void setExplicitSearch(boolean value) {
    explicitSearch = value;
  }

  // Keep explicit criteria separate so a refinement can apply today's session preferences.
  private SessionPreferences searchCriteria;
  private UserPreferenceEmbedding userPreferenceSnapshot;
  // Freeze absence too: a MORE refill must not pick up a newly imported reading profile.
  private boolean userPreferencesLoaded;

  public SessionPreferences getSearchCriteria() {
    return searchCriteria;
  }

  public void setSearchCriteria(SessionPreferences value) {
    searchCriteria = value;
  }

  public UserPreferenceEmbedding getUserPreferenceSnapshot() {
    return userPreferenceSnapshot;
  }

  public void setUserPreferenceSnapshot(UserPreferenceEmbedding value) {
    userPreferenceSnapshot = value;
  }

  public boolean isUserPreferencesLoaded() {
    return userPreferencesLoaded;
  }

  public void setUserPreferencesLoaded(boolean value) {
    userPreferencesLoaded = value;
  }

  private Intent intent;

  private SemanticQuery positiveSemanticQuery;
  private SemanticQuery negativeSemanticQuery;

  private MustInclude mustInclude;
  private MustNotInclude mustNotInclude;
  private Integer bookCount;
  private boolean personalised;
  private boolean preferenceClarificationRequired;
  private boolean searchClarificationRequired;
  private boolean noMoreRecommendations;

  public boolean isNoMoreRecommendations() {
    return noMoreRecommendations;
  }

  public void setNoMoreRecommendations(boolean exhausted) {
    noMoreRecommendations = exhausted;
  }

  public boolean isSearchClarificationRequired() {
    return searchClarificationRequired;
  }

  public void setSearchClarificationRequired(boolean required) {
    searchClarificationRequired = required;
  }

  public boolean isPersonalised() {
    return personalised;
  }

  public void setPersonalised(boolean personalised) {
    this.personalised = personalised;
  }

  public boolean isPreferenceClarificationRequired() {
    return preferenceClarificationRequired;
  }

  public void setPreferenceClarificationRequired(boolean required) {
    this.preferenceClarificationRequired = required;
  }

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
