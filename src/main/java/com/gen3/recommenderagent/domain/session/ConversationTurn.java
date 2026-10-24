package com.gen3.recommenderagent.domain.session;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationTurn {
  private SearchContext searchContext = new SearchContext();

  @com.fasterxml.jackson.annotation.JsonIgnore
  public SearchContext getSearchContext() {
    return searchContext;
  }

  public void setSearchContext(SearchContext value) {
    searchContext = value;
  }

  private String requestId;

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String value) {
    requestId = value;
  }

  private String rawText;

  public String getRawText() {
    return rawText;
  }

  public void setRawText(String value) {
    rawText = value;
  }

  private java.time.Instant createdAt;

  public java.time.Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(java.time.Instant value) {
    createdAt = value;
  }

  private com.gen3.recommenderagent.domain.Intent intent;

  public com.gen3.recommenderagent.domain.Intent getIntent() {
    return intent;
  }

  public void setIntent(com.gen3.recommenderagent.domain.Intent value) {
    intent = value;
  }

  private Integer referenceRank;

  public Integer getReferenceRank() {
    return referenceRank;
  }

  public void setReferenceRank(Integer value) {
    referenceRank = value;
  }

  private String referenceTitle;

  public String getReferenceTitle() {
    return referenceTitle;
  }

  public void setReferenceTitle(String value) {
    referenceTitle = value;
  }

  private Integer bookCount;

  public Integer getBookCount() {
    return bookCount;
  }

  public void setBookCount(Integer value) {
    bookCount = value;
  }

  private ConversationDecision decision;

  @com.fasterxml.jackson.annotation.JsonIgnore
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

  public static ConversationTurn fromDraft(ConversationDraft draft) {
    var turn = new ConversationTurn();
    turn.searchContext = draft.getSearchContext().copy();
    turn.setRequestId(draft.getRequestId());
    turn.setRawText(draft.getRawText());
    turn.setCreatedAt(draft.getCreatedAt());
    turn.setIntent(draft.getIntent());
    turn.setReferenceRank(draft.getReferenceRank());
    turn.setReferenceTitle(draft.getReferenceTitle());
    turn.setBookCount(draft.getBookCount());
    turn.setDecision(draft.getDecision());
    turn.setPreferenceClarificationRequired(draft.isPreferenceClarificationRequired());
    turn.setSearchClarificationRequired(draft.isSearchClarificationRequired());
    turn.setNoMoreRecommendations(draft.isNoMoreRecommendations());
    turn.setRecommendations(draft.getRecommendations());
    return turn;
  }

  public ConversationDraft toDraft() {
    var draft = new ConversationDraft();
    draft.setSearchContext(searchContext.copy());
    draft.setRequestId(getRequestId());
    draft.setRawText(getRawText());
    draft.setCreatedAt(getCreatedAt());
    draft.setIntent(getIntent());
    draft.setReferenceRank(getReferenceRank());
    draft.setReferenceTitle(getReferenceTitle());
    draft.setBookCount(getBookCount());
    draft.setDecision(getDecision());
    draft.setPreferenceClarificationRequired(isPreferenceClarificationRequired());
    draft.setSearchClarificationRequired(isSearchClarificationRequired());
    draft.setNoMoreRecommendations(isNoMoreRecommendations());
    draft.setRecommendations(getRecommendations());
    return draft;
  }

  public ConversationTurn snapshot() {
    var copy = fromDraft(toDraft());
    copy.setRawText(null);
    return copy;
  }
}
