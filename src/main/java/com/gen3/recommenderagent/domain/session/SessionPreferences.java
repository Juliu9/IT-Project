package com.gen3.recommenderagent.domain.session;

/** Search preferences retained for the lifetime of one recommendation session. */
public class SessionPreferences {

  private SemanticQuery positiveSemanticQuery;
  private SemanticQuery negativeSemanticQuery;
  private MustInclude mustInclude;
  private MustNotInclude mustNotInclude;

  public SemanticQuery getPositiveSemanticQuery() {
    return positiveSemanticQuery;
  }

  public void setPositiveSemanticQuery(SemanticQuery positiveSemanticQuery) {
    this.positiveSemanticQuery = positiveSemanticQuery;
  }

  public SemanticQuery getNegativeSemanticQuery() {
    return negativeSemanticQuery;
  }

  public void setNegativeSemanticQuery(SemanticQuery negativeSemanticQuery) {
    this.negativeSemanticQuery = negativeSemanticQuery;
  }

  public MustInclude getMustInclude() {
    return mustInclude;
  }

  public void setMustInclude(MustInclude mustInclude) {
    this.mustInclude = mustInclude;
  }

  public MustNotInclude getMustNotInclude() {
    return mustNotInclude;
  }

  public void setMustNotInclude(MustNotInclude mustNotInclude) {
    this.mustNotInclude = mustNotInclude;
  }
}
