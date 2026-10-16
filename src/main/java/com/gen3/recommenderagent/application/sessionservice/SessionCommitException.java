package com.gen3.recommenderagent.application.sessionservice;

/** Signals that a completed session transition could not be committed safely. */
public class SessionCommitException extends RuntimeException {

  public SessionCommitException() {
    super("The session could not be updated safely; please try again shortly.");
  }
}
