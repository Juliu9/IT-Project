package com.gen3.recommenderagent.storage.sessionrepository.redis;

/** Signals that another request already owns the session update lock. */
public class SessionBusyException extends RuntimeException {

  public SessionBusyException() {
    super("Another request is already being processed for this session; please retry");
  }
}
