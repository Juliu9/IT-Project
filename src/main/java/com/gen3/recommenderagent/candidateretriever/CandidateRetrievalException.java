package com.gen3.recommenderagent.candidateretriever;

/** Signals that the configured candidate store could not complete a search. */
public class CandidateRetrievalException extends RuntimeException {

  public CandidateRetrievalException(String backend, Throwable cause) {
    super("Candidate retrieval from " + backend + " is temporarily unavailable", cause);
  }
}
