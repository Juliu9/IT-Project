package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SearchContext;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.util.List;

/** Private-to-conversation working state; never used as a persisted turn or response contract. */
public class ConversationDraft extends SessionRequest {
  /** Retains structured context without persisting the user's original message. */
  public static ConversationTurn toStoredTurn(ConversationDraft draft) {
    var turn = toTurn(draft);
    turn.setRawText(null);
    return turn;
  }

  public static ConversationTurn toTurn(ConversationDraft draft) {
    var turn = new ConversationTurn();
    turn.setSearchContext(draft.getSearchContext().copy());
    turn.setSimilarBookChoices(draft.getSimilarBookChoices());
    turn.setRequestId(draft.getRequestId());
    turn.setRawText(draft.getRawText());
    turn.setCreatedAt(draft.getCreatedAt());
    turn.setIntent(draft.getIntent());
    turn.setExplanationSelection(draft.getExplanationSelection());
    turn.setReferenceRank(draft.getReferenceRank());
    turn.setReferenceTitle(draft.getReferenceTitle());
    turn.setBookCount(draft.getBookCount());
    turn.setInvalidBookCount(draft.isInvalidBookCount());
    turn.setDecision(draft.getDecision());
    turn.setPendingQuestion(draft.getPendingQuestion());
    turn.setPreferenceClarificationRequired(draft.isPreferenceClarificationRequired());
    turn.setSearchClarificationRequired(draft.isSearchClarificationRequired());
    turn.setNoMoreRecommendations(draft.isNoMoreRecommendations());
    turn.setRecommendations(draft.getRecommendations());
    return turn;
  }

  public static ConversationDraft fromTurn(ConversationTurn turn) {
    var draft = new ConversationDraft();
    draft.setSearchContext(turn.getSearchContext().copy());
    draft.setSimilarBookChoices(turn.getSimilarBookChoices());
    draft.setRequestId(turn.getRequestId());
    draft.setRawText(turn.getRawText());
    draft.setCreatedAt(turn.getCreatedAt());
    draft.setIntent(turn.getIntent());
    draft.setExplanationSelection(turn.getExplanationSelection());
    draft.setReferenceRank(turn.getReferenceRank());
    draft.setReferenceTitle(turn.getReferenceTitle());
    draft.setBookCount(turn.getBookCount());
    draft.setInvalidBookCount(turn.isInvalidBookCount());
    draft.setDecision(turn.getDecision());
    draft.setPendingQuestion(turn.getPendingQuestion());
    draft.setPreferenceClarificationRequired(turn.isPreferenceClarificationRequired());
    draft.setSearchClarificationRequired(turn.isSearchClarificationRequired());
    draft.setNoMoreRecommendations(turn.isNoMoreRecommendations());
    draft.setRecommendations(turn.getRecommendations());
    return draft;
  }

  private boolean preferenceAnswer;

  public boolean isPreferenceAnswer() {
    return preferenceAnswer;
  }

  public void setPreferenceAnswer(boolean value) {
    preferenceAnswer = value;
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
}
