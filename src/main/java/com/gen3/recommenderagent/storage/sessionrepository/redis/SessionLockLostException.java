package com.gen3.recommenderagent.storage.sessionrepository.redis;

/** Signals that a session lock expired or changed owner before its transition completed. */
public class SessionLockLostException extends RuntimeException {

  public SessionLockLostException() {
    super("I couldn't save that request safely. Please try again in a moment.");
  }
}
