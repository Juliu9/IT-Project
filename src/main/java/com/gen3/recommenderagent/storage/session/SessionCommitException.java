package com.gen3.recommenderagent.storage.session;

/** Signals that a completed session transition could not be committed safely. */
public class SessionCommitException extends RuntimeException {

  /** Creates the failure used when a session update cannot be committed safely. */
  public SessionCommitException() {
    super("I couldn't save that request safely. Please try again in a moment.");
  }
}
