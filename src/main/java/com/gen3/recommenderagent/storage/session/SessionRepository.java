package com.gen3.recommenderagent.storage.session;

import com.gen3.recommenderagent.domain.session.Session;
import java.util.function.Function;

/** Owns the ordered, exclusive update of one user's session. */
public interface SessionRepository {

  /**
   * Loads a best-effort snapshot for parser context. It never grants authority to mutate the
   * session; the final intent is still resolved inside {@link #update}.
   */
  default Session snapshot(String sessionId, String userId) {
    return null;
  }

  /**
   * Locks and loads (or creates) the session, applies the change once, then commits before
   * returning its result. A failed change is not saved. Busy sessions and rejected commits throw;
   * the change is never retried automatically. The change must run synchronously and must not
   * change the session's identity or version. Implementations must coordinate across application
   * instances, reject concurrent updates with SessionBusyException, and expire sessions 24 hours
   * after the last successful commit. Rejected commits throw SessionCommitException; lost update
   * ownership may throw SessionLockLostException.
   */
  default <T> T update(String sessionId, String userId, Function<Session, T> change) {
    return update(sessionId, userId, change, Function.identity());
  }

  /**
   * Runs afterCommit only after saving, while still excluding same-session updates. Its failure
   * cannot roll back the saved session. Neither callback is retried.
   */
  <T, R> R update(
      String sessionId, String userId, Function<Session, T> change, Function<T, R> afterCommit);
}
