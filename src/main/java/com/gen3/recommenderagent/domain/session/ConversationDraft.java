package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.util.List;

/** Private-to-conversation working state; never used as a persisted turn or response contract. */
public class ConversationDraft extends SessionRequest {
  private SearchContext searchContext = new SearchContext();
  private ConversationDecision decision;
  private boolean preferenceClarificationRequired;
  private boolean searchClarificationRequired;
  private boolean noMoreRecommendations;
  private List<Recommendation> recommendations;

  public SearchContext getSearchContext() {
    return searchContext;
  }

  public void setSearchContext(SearchContext value) {
    searchContext = value;
  }

  public ConversationDecision getDecision() {
    return decision;
  }

  public void setDecision(ConversationDecision value) {
    decision = value;
  }

  public boolean isPreferenceClarificationRequired() {
    return preferenceClarificationRequired;
  }

  public void setPreferenceClarificationRequired(boolean value) {
    preferenceClarificationRequired = value;
  }

  public boolean isSearchClarificationRequired() {
    return searchClarificationRequired;
  }

  public void setSearchClarificationRequired(boolean value) {
    searchClarificationRequired = value;
  }

  public boolean isNoMoreRecommendations() {
    return noMoreRecommendations;
  }

  public void setNoMoreRecommendations(boolean value) {
    noMoreRecommendations = value;
  }

  public List<Recommendation> getRecommendations() {
    return recommendations;
  }

  public void setRecommendations(List<Recommendation> value) {
    recommendations = value;
  }

  public SessionPreferences getSearchCriteria() {
    return searchContext.getSearchCriteria();
  }

  public void setSearchCriteria(SessionPreferences value) {
    searchContext.setSearchCriteria(value);
  }

  public UserPreferenceEmbedding getUserPreferenceSnapshot() {
    return searchContext.getUserPreferenceSnapshot();
  }

  public void setUserPreferenceSnapshot(UserPreferenceEmbedding value) {
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
