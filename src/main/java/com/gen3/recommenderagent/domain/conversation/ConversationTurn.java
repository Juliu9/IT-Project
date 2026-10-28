package com.gen3.recommenderagent.domain.conversation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SearchContext;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.search.SimilarBookReference;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationTurn {
  private boolean invalidBookCount;

  public boolean isInvalidBookCount() {
    return invalidBookCount;
  }

  public void setInvalidBookCount(boolean value) {
    invalidBookCount = value;
  }

  private ConversationDecision pendingQuestion;

  public ConversationDecision getPendingQuestion() {
    return pendingQuestion;
  }

  public void setPendingQuestion(ConversationDecision value) {
    pendingQuestion = value;
  }

  private List<com.gen3.catalogue.model.AudiobookTitle> similarBookChoices = List.of();

  public List<com.gen3.catalogue.model.AudiobookTitle> getSimilarBookChoices() {
    return similarBookChoices;
  }

  public void setSimilarBookChoices(List<com.gen3.catalogue.model.AudiobookTitle> value) {
    similarBookChoices = value;
  }

  private SearchContext searchContext = new SearchContext();

  public List<String> getReplaceSearchFields() {
    return searchContext.getReplaceSearchFields();
  }

  public void setReplaceSearchFields(List<String> value) {
    searchContext.setReplaceSearchFields(value);
  }

  private ExplanationSelection explanationSelection;

  public ExplanationSelection getExplanationSelection() {
    return explanationSelection;
  }

  public void setExplanationSelection(ExplanationSelection value) {
    explanationSelection = value;
  }

  private String requestId;
  private String rawText;
  private Instant createdAt;
  private ConversationAction intent;
  private Integer referenceRank;
  private String referenceTitle;
  private Integer bookCount;
  private ConversationDecision decision;
  private boolean preferenceClarificationRequired;
  private boolean searchClarificationRequired;
  private boolean noMoreRecommendations;
  private List<Recommendation> recommendations;

  @JsonIgnore
  public SearchContext getSearchContext() {
    return searchContext;
  }

  public void setSearchContext(SearchContext value) {
    searchContext = value;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String value) {
    requestId = value;
  }

  public String getRawText() {
    return rawText;
  }

  public void setRawText(String value) {
    rawText = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    createdAt = value;
  }

  public ConversationAction getIntent() {
    return intent;
  }

  public void setIntent(ConversationAction value) {
    intent = value;
  }

  public Integer getReferenceRank() {
    return referenceRank;
  }

  public void setReferenceRank(Integer value) {
    referenceRank = value;
  }

  public String getReferenceTitle() {
    return referenceTitle;
  }

  public void setReferenceTitle(String value) {
    referenceTitle = value;
  }

  public Integer getBookCount() {
    return bookCount;
  }

  public void setBookCount(Integer value) {
    bookCount = value;
  }

  @JsonIgnore
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

  public ExactCriteria getMustInclude() {
    return searchContext.getMustInclude();
  }

  public void setMustInclude(ExactCriteria value) {
    searchContext.setMustInclude(value);
  }

  public ExactCriteria getMustNotInclude() {
    return searchContext.getMustNotInclude();
  }

  public void setMustNotInclude(ExactCriteria value) {
    searchContext.setMustNotInclude(value);
  }

  public SimilarBookReference getSimilarBook() {
    return searchContext.getSimilarBook();
  }

  public void setSimilarBook(SimilarBookReference value) {
    searchContext.setSimilarBook(value);
  }

  public float[] getSimilarityQueryVector() {
    return searchContext.getSimilarityQueryVector();
  }

  public void setSimilarityQueryVector(float[] value) {
    searchContext.setSimilarityQueryVector(value);
  }
}
