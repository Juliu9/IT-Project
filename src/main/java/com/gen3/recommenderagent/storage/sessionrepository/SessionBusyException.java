package com.gen3.recommenderagent.storage.sessionrepository;

/** Signals that another request already owns the session update lock. */
public class SessionBusyException extends RuntimeException {

  public SessionBusyException() {
    super("I'm still finishing your previous request. Please try again in a moment.");
  }
}
