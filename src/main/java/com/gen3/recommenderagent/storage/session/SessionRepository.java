package com.gen3.recommenderagent.storage.session;

import com.gen3.recommenderagent.domain.session.Session;
import java.util.function.Function;

/** Owns the ordered, exclusive update of one user's session. */
public interface SessionRepository {

  /**
   * Locks and loads (or creates) the session, applies the change once, then commits before
   * returning its result. A failed change is not saved. Busy sessions and rejected commits throw;
   * the change is never retried automatically. The change must run synchronously and must not
   * change the session's identity or version. Implementations must coordinate across application
   * instances, reject concurrent updates with SessionBusyException, and expire sessions 24 hours
   * after the last successful commit. Rejected commits throw SessionCommitException; lost update
   * ownership may throw SessionLockLostException.
   */
  <T> T update(String sessionId, String userId, Function<Session, T> change);
}
