package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;

/** Explicit and effective search criteria together with the frozen reading-preference evidence. */
public class SearchContext {
  private SessionPreferences searchCriteria;
  private UserPreferenceEmbedding userPreferenceSnapshot;
  private boolean userPreferencesLoaded;
  private boolean personalised;
  private SemanticQuery positiveSemanticQuery;
  private SemanticQuery negativeSemanticQuery;
  private ExactCriteria mustInclude;
  private ExactCriteria mustNotInclude;

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

  public boolean isPersonalised() {
    return personalised;
  }

  public void setPersonalised(boolean value) {
    personalised = value;
  }

  public SemanticQuery getPositiveSemanticQuery() {
    return positiveSemanticQuery;
  }

  public void setPositiveSemanticQuery(SemanticQuery value) {
    positiveSemanticQuery = value;
  }

  public SemanticQuery getNegativeSemanticQuery() {
    return negativeSemanticQuery;
  }

  public void setNegativeSemanticQuery(SemanticQuery value) {
    negativeSemanticQuery = value;
  }

  public ExactCriteria getMustInclude() {
    return mustInclude;
  }

  public void setMustInclude(ExactCriteria value) {
    mustInclude = value;
  }

  public ExactCriteria getMustNotInclude() {
    return mustNotInclude;
  }

  public void setMustNotInclude(ExactCriteria value) {
    mustNotInclude = value;
  }

  public SearchContext copy() {
    var copy = new SearchContext();
    var values = new SessionPreferences();
    values.setPositiveSemanticQuery(positiveSemanticQuery);
    values.setNegativeSemanticQuery(negativeSemanticQuery);
    values.setMustInclude(mustInclude);
    values.setMustNotInclude(mustNotInclude);
    values = values.copy();
    copy.positiveSemanticQuery = values.getPositiveSemanticQuery();
    copy.negativeSemanticQuery = values.getNegativeSemanticQuery();
    copy.mustInclude = mustInclude == null ? null : values.getMustInclude();
    copy.mustNotInclude = mustNotInclude == null ? null : values.getMustNotInclude();
    copy.searchCriteria = searchCriteria == null ? null : searchCriteria.copy();
    copy.userPreferencesLoaded = userPreferencesLoaded;
    copy.personalised = personalised;
    if (userPreferenceSnapshot != null) {
      var favourites = userPreferenceSnapshot.favouritesVector();
      var history = userPreferenceSnapshot.historyVector();
      copy.userPreferenceSnapshot =
          new UserPreferenceEmbedding(
              userPreferenceSnapshot.userId(),
              favourites == null ? null : favourites.clone(),
              history == null ? null : history.clone());
    }
    return copy;
  }
}
