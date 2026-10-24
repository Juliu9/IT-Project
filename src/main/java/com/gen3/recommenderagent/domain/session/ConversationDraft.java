package com.gen3.recommenderagent.domain.session;

/** Private-to-conversation working state; never used as a persisted turn or response contract. */
public class ConversationDraft extends SessionRequest {
  private SearchContext searchContext = new SearchContext();

  public SearchContext getSearchContext() {
    return searchContext;
  }

  public void setSearchContext(SearchContext value) {
    searchContext = value;
  }

  private ConversationDecision decision;

  public ConversationDecision getDecision() {
    return decision;
  }

  public void setDecision(ConversationDecision value) {
    decision = value;
  }

  private boolean preferenceClarificationRequired;

  public boolean isPreferenceClarificationRequired() {
    return preferenceClarificationRequired;
  }

  public void setPreferenceClarificationRequired(boolean value) {
    preferenceClarificationRequired = value;
  }

  private boolean searchClarificationRequired;

  public boolean isSearchClarificationRequired() {
    return searchClarificationRequired;
  }

  public void setSearchClarificationRequired(boolean value) {
    searchClarificationRequired = value;
  }

  private boolean noMoreRecommendations;

  public boolean isNoMoreRecommendations() {
    return noMoreRecommendations;
  }

  public void setNoMoreRecommendations(boolean value) {
    noMoreRecommendations = value;
  }

  private java.util.List<Recommendation> recommendations;

  public java.util.List<Recommendation> getRecommendations() {
    return recommendations;
  }

  public void setRecommendations(java.util.List<Recommendation> value) {
    recommendations = value;
  }

  public SessionPreferences getSearchCriteria() {
    return searchContext.getSearchCriteria();
  }

  public void setSearchCriteria(SessionPreferences value) {
    searchContext.setSearchCriteria(value);
  }

  public com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding
      getUserPreferenceSnapshot() {
    return searchContext.getUserPreferenceSnapshot();
  }

  public void setUserPreferenceSnapshot(
      com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding value) {
    searchContext.setUserPreferenceSnapshot(value);
  }

  public boolean isUserPreferencesLoaded() {
    return searchContext.isUserPreferencesLoaded();
  }

  public void setUserPreferencesLoaded(boolean value) {
    searchContext.setUserPreferencesLoaded(value);
  }

  public boolean isPersonalised() {
    return searchContext.isPersonalised();
  }

  public void setPersonalised(boolean value) {
    searchContext.setPersonalised(value);
  }

  public SemanticQuery getPositiveSemanticQuery() {
    return searchContext.getPositiveSemanticQuery();
  }

  public void setPositiveSemanticQuery(SemanticQuery value) {
    searchContext.setPositiveSemanticQuery(value);
  }

  public SemanticQuery getNegativeSemanticQuery() {
    return searchContext.getNegativeSemanticQuery();
  }

  public void setNegativeSemanticQuery(SemanticQuery value) {
    searchContext.setNegativeSemanticQuery(value);
  }

  public MustInclude getMustInclude() {
    return searchContext.getMustInclude();
  }

  public void setMustInclude(MustInclude value) {
    searchContext.setMustInclude(value);
  }

  public MustNotInclude getMustNotInclude() {
    return searchContext.getMustNotInclude();
  }

  public void setMustNotInclude(MustNotInclude value) {
    searchContext.setMustNotInclude(value);
  }
}
