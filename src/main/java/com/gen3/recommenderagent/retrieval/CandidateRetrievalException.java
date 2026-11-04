package com.gen3.recommenderagent.retrieval;

/** Signals that the configured candidate store could not complete a search. */
public class CandidateRetrievalException extends RuntimeException {

  /** Creates the failure with the supplied message or underlying cause. */
  public CandidateRetrievalException(String backend, Throwable cause) {
    super("Candidate retrieval from " + backend + " is temporarily unavailable", cause);
  }
}
