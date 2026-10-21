package com.gen3.recommenderagent.storage.sessionrepository;

/** Signals that a completed session transition could not be committed safely. */
public class SessionCommitException extends RuntimeException {

  public SessionCommitException() {
    super("I couldn't save that request safely. Please try again in a moment.");
  }
}
