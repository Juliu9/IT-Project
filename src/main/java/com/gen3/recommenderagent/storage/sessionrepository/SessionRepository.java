package com.gen3.recommenderagent.storage.sessionrepository;

import com.gen3.recommenderagent.domain.session.Session;
import java.util.function.Function;

/** Owns the ordered, exclusive update of one user's session. */
public interface SessionRepository {

  /**
   * Locks and loads (or creates) the session, applies the change once, then commits before
   * returning its result. A failed change is not saved. Busy sessions and rejected commits throw;
   * the change is never retried automatically. The change must run synchronously and must not
   * change the session's identity or version.
   */
  <T> T update(String sessionId, String userId, Function<Session, T> change);
}
