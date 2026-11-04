package com.gen3.recommenderagent.storage.session;

/** Signals that a session lock expired or changed owner before its transition completed. */
public class SessionLockLostException extends RuntimeException {

  /** Creates the failure used when a request loses its session lock ownership. */
  public SessionLockLostException() {
    super("I couldn't save that request safely. Please try again in a moment.");
  }
}
