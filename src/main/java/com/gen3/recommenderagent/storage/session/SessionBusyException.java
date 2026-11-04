package com.gen3.recommenderagent.storage.session;

/** Signals that another request already owns the session update lock. */
public class SessionBusyException extends RuntimeException {

  /** Creates the failure used when another request owns the same session. */
  public SessionBusyException() {
    super("I'm still finishing your previous request. Please try again in a moment.");
  }
}
