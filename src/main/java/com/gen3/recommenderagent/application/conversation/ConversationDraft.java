package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
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
  private java.util.Set<String> readBookIds = java.util.Set.of();

  /** Request-local eligibility snapshot; deliberately not copied into stored turns or sessions. */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public java.util.Set<String> getReadBookIds() {
    return readBookIds;
  }

  public void setReadBookIds(java.util.Set<String> value) {
    readBookIds = java.util.Set.copyOf(value);
  }

  /** Retains structured context without persisting the user's original message. */
  public static ConversationTurn toStoredTurn(ConversationDraft draft) {
    var turn = toTurn(draft);
    turn.setRawText(null);
    return turn;
  }

  /**
   * Snapshots the working draft into a conversation turn with copied search and preference state.
   */
  public static ConversationTurn toTurn(ConversationDraft draft) {
    var turn = new ConversationTurn();
    turn.setSearchContext(draft.getSearchContext().copy());
    turn.setSimilarBookChoices(draft.getSimilarBookChoices());
    turn.setRequestId(draft.getRequestId());
    turn.setRawText(draft.getRawText());
    turn.setCreatedAt(draft.getCreatedAt());
    turn.setIntent(draft.getIntent());
    turn.setResponseAction(draft.getResponseAction());
    turn.setExplanationSelection(draft.getExplanationSelection());
    turn.setReferenceRank(draft.getReferenceRank());
    turn.setReferenceTitle(draft.getReferenceTitle());
    turn.setSessionPreferenceSnapshot(
        draft.getSessionPreferenceSnapshot() == null
            ? null
            : draft.getSessionPreferenceSnapshot().copy());
    turn.setBookCount(draft.getBookCount());
    turn.setInvalidBookCount(draft.isInvalidBookCount());
    turn.setOutcome(draft.getOutcome());
    turn.setPendingQuestion(draft.getPendingQuestion());
    turn.setPreferenceClarificationRequired(draft.isPreferenceClarificationRequired());
    turn.setSearchClarificationRequired(draft.isSearchClarificationRequired());
    turn.setNoMoreRecommendations(draft.isNoMoreRecommendations());
    turn.setRecommendations(draft.getRecommendations());
    return turn;
  }

  /** Restores a working draft from a retained conversation snapshot. */
  public static ConversationDraft fromTurn(ConversationTurn turn) {
    var draft = new ConversationDraft();
    draft.setSearchContext(turn.getSearchContext().copy());
    draft.setSimilarBookChoices(turn.getSimilarBookChoices());
    draft.setRequestId(turn.getRequestId());
    draft.setRawText(turn.getRawText());
    draft.setCreatedAt(turn.getCreatedAt());
    draft.setIntent(turn.getIntent());
    draft.setResponseAction(turn.getResponseAction());
    draft.setExplanationSelection(turn.getExplanationSelection());
    draft.setReferenceRank(turn.getReferenceRank());
    draft.setReferenceTitle(turn.getReferenceTitle());
    draft.setSessionPreferenceSnapshot(
        turn.getSessionPreferenceSnapshot() == null
            ? null
            : turn.getSessionPreferenceSnapshot().copy());
    draft.setBookCount(turn.getBookCount());
    draft.setInvalidBookCount(turn.isInvalidBookCount());
    draft.setOutcome(turn.getOutcome());
    draft.setPendingQuestion(turn.getPendingQuestion());
    draft.setPreferenceClarificationRequired(turn.isPreferenceClarificationRequired());
    draft.setSearchClarificationRequired(turn.isSearchClarificationRequired());
    draft.setNoMoreRecommendations(turn.isNoMoreRecommendations());
    draft.setRecommendations(turn.getRecommendations());
    return draft;
  }

  private SessionPreferences sessionPreferenceSnapshot;

  public SessionPreferences getSessionPreferenceSnapshot() {
    return sessionPreferenceSnapshot;
  }

  public void setSessionPreferenceSnapshot(SessionPreferences value) {
    sessionPreferenceSnapshot = value;
  }

  private boolean preferenceAnswer;

  public boolean isPreferenceAnswer() {
    return preferenceAnswer;
  }

  public void setPreferenceAnswer(boolean value) {
    preferenceAnswer = value;
  }

  private ConversationQuestion pendingQuestion;

  public ConversationQuestion getPendingQuestion() {
    return pendingQuestion;
  }

  public void setPendingQuestion(ConversationQuestion value) {
    pendingQuestion = value;
  }

  private List<com.gen3.shared.audiobookcatalogue.model.AudiobookTitle> similarBookChoices =
      List.of();

  public List<com.gen3.shared.audiobookcatalogue.model.AudiobookTitle> getSimilarBookChoices() {
    return similarBookChoices;
  }

  public void setSimilarBookChoices(
      List<com.gen3.shared.audiobookcatalogue.model.AudiobookTitle> value) {
    similarBookChoices = value;
  }

  private SearchContext searchContext = new SearchContext();

  public List<String> getReplaceSearchFields() {
    return searchContext.getReplaceSearchFields();
  }

  public void setReplaceSearchFields(List<String> value) {
    searchContext.setReplaceSearchFields(value);
  }

  private ConversationOutcome outcome;
  private ConversationAction responseAction;
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

  public ConversationOutcome getOutcome() {
    return outcome;
  }

  public void setOutcome(ConversationOutcome value) {
    outcome = value;
  }

  public ConversationAction getResponseAction() {
    return responseAction;
  }

  public void setResponseAction(ConversationAction value) {
    responseAction = value;
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

  /** {@inheritDoc} */
  @Override
  public boolean requiresSemanticRetrieval() {
    return isPersonalised() || searchContext.getSimilarBook() != null;
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
