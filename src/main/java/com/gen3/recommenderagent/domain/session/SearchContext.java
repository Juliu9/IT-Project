package com.gen3.recommenderagent.domain.session;

/** Explicit and effective search criteria together with the frozen reading-preference evidence. */
public class SearchContext {
  private SessionPreferences searchCriteria;

  public SessionPreferences getSearchCriteria() {
    return searchCriteria;
  }

  public void setSearchCriteria(SessionPreferences value) {
    searchCriteria = value;
  }

  private com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding
      userPreferenceSnapshot;

  public com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding
      getUserPreferenceSnapshot() {
    return userPreferenceSnapshot;
  }

  public void setUserPreferenceSnapshot(
      com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding value) {
    userPreferenceSnapshot = value;
  }

  private boolean userPreferencesLoaded;

  public boolean isUserPreferencesLoaded() {
    return userPreferencesLoaded;
  }

  public void setUserPreferencesLoaded(boolean value) {
    userPreferencesLoaded = value;
  }

  private boolean personalised;

  public boolean isPersonalised() {
    return personalised;
  }

  public void setPersonalised(boolean value) {
    personalised = value;
  }

  private SemanticQuery positiveSemanticQuery;

  public SemanticQuery getPositiveSemanticQuery() {
    return positiveSemanticQuery;
  }

  public void setPositiveSemanticQuery(SemanticQuery value) {
    positiveSemanticQuery = value;
  }

  private SemanticQuery negativeSemanticQuery;

  public SemanticQuery getNegativeSemanticQuery() {
    return negativeSemanticQuery;
  }

  public void setNegativeSemanticQuery(SemanticQuery value) {
    negativeSemanticQuery = value;
  }

  private MustInclude mustInclude;

  public MustInclude getMustInclude() {
    return mustInclude;
  }

  public void setMustInclude(MustInclude value) {
    mustInclude = value;
  }

  private MustNotInclude mustNotInclude;

  public MustNotInclude getMustNotInclude() {
    return mustNotInclude;
  }

  public void setMustNotInclude(MustNotInclude value) {
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
          new com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding(
              userPreferenceSnapshot.userId(),
              favourites == null ? null : favourites.clone(),
              history == null ? null : history.clone());
    }
    return copy;
  }
}
