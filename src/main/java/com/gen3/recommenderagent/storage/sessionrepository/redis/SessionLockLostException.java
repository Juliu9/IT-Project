package com.gen3.recommenderagent.storage.sessionrepository.redis;

/** Signals that a session lock expired or changed owner before its transition completed. */
public class SessionLockLostException extends RuntimeException {

  public SessionLockLostException() {
    super("The session could not be updated safely; please try again shortly.");
  }
}
