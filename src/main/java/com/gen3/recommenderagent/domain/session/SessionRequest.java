package com.gen3.recommenderagent.domain.session;

/** Incoming conversation instructions, independent of results and persisted search snapshots. */
public class SessionRequest {
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

  private boolean addAuthors;

  public boolean isAddAuthors() {
    return addAuthors;
  }

  public void setAddAuthors(boolean value) {
    addAuthors = value;
  }

  private boolean addNarrators;

  public boolean isAddNarrators() {
    return addNarrators;
  }

  public void setAddNarrators(boolean value) {
    addNarrators = value;
  }

  private SessionPreferences preferenceUpdates;

  public SessionPreferences getPreferenceUpdates() {
    return preferenceUpdates;
  }

  public void setPreferenceUpdates(SessionPreferences value) {
    preferenceUpdates = value;
  }

  private SessionPreferences preferenceRemovals;

  public SessionPreferences getPreferenceRemovals() {
    return preferenceRemovals;
  }

  public void setPreferenceRemovals(SessionPreferences value) {
    preferenceRemovals = value;
  }

  private java.util.List<String> replacePreferenceFields;

  public java.util.List<String> getReplacePreferenceFields() {
    return replacePreferenceFields;
  }

  public void setReplacePreferenceFields(java.util.List<String> value) {
    replacePreferenceFields = value;
  }

  private boolean explicitSearch;

  public boolean isExplicitSearch() {
    return explicitSearch;
  }

  public void setExplicitSearch(boolean value) {
    explicitSearch = value;
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

  private Integer bookCount;

  public Integer getBookCount() {
    return bookCount;
  }

  public void setBookCount(Integer value) {
    bookCount = value;
  }

  private boolean personalised;

  public boolean isPersonalised() {
    return personalised;
  }

  public void setPersonalised(boolean value) {
    personalised = value;
  }
}
