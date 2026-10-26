package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.domain.Intent;
import java.time.Instant;
import java.util.List;

/** Incoming conversation instructions, independent of results and persisted search snapshots. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class SessionRequest {
  private boolean longer;
  private boolean invalidBookCount;
  private ExplanationSelection explanationSelection;
  private List<String> preferenceRemovalTerms = List.of();
  private boolean positivePreferenceRemovalOnly;

  public boolean isLonger() {
    return longer;
  }

  public void setLonger(boolean value) {
    longer = value;
  }

  public boolean isInvalidBookCount() {
    return invalidBookCount;
  }

  public void setInvalidBookCount(boolean value) {
    invalidBookCount = value;
  }

  public ExplanationSelection getExplanationSelection() {
    return explanationSelection;
  }

  public void setExplanationSelection(ExplanationSelection value) {
    explanationSelection = value;
  }

  public List<String> getPreferenceRemovalTerms() {
    return preferenceRemovalTerms;
  }

  public void setPreferenceRemovalTerms(List<String> value) {
    preferenceRemovalTerms = value == null ? List.of() : value;
  }

  public boolean isPositivePreferenceRemovalOnly() {
    return positivePreferenceRemovalOnly;
  }

  public void setPositivePreferenceRemovalOnly(boolean value) {
    positivePreferenceRemovalOnly = value;
  }

  private List<String> replaceSearchFields = List.of();

  public List<String> getReplaceSearchFields() {
    return replaceSearchFields;
  }

  public void setReplaceSearchFields(List<String> value) {
    replaceSearchFields = value == null ? List.of() : value;
  }

  private boolean shorter;

  public boolean isShorter() {
    return shorter;
  }

  public void setShorter(boolean value) {
    shorter = value;
  }

  private String similarToTitle;
  private String similarToAuthor;
  private boolean similarToClarificationRequired;

  public String getSimilarToTitle() {
    return similarToTitle;
  }

  public void setSimilarToTitle(String value) {
    similarToTitle = value;
  }

  public String getSimilarToAuthor() {
    return similarToAuthor;
  }

  public void setSimilarToAuthor(String value) {
    similarToAuthor = value;
  }

  public boolean isSimilarToClarificationRequired() {
    return similarToClarificationRequired;
  }

  public void setSimilarToClarificationRequired(boolean value) {
    similarToClarificationRequired = value;
  }

  private String requestId;
  private String rawText;
  private Instant createdAt;
  private Intent intent;
  private Integer referenceRank;
  private String referenceTitle;
  private boolean addAuthors;
  private boolean addNarrators;
  private SessionPreferences preferenceUpdates;
  private SessionPreferences preferenceRemovals;
  private List<String> replacePreferenceFields;
  private boolean explicitSearch;
  private SemanticQuery positiveSemanticQuery;
  private SemanticQuery negativeSemanticQuery;
  private ExactCriteria mustInclude;
  private ExactCriteria mustNotInclude;
  private Integer bookCount;
  private boolean personalised;

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

  public Intent getIntent() {
    return intent;
  }

  public void setIntent(Intent value) {
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

  public Integer getBookCount() {
    return bookCount;
  }

  public void setBookCount(Integer value) {
    bookCount = value;
  }

  public boolean isPersonalised() {
    return personalised;
  }

  public void setPersonalised(boolean value) {
    personalised = value;
  }
}
