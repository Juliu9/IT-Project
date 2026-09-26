package com.gen3.recommenderagent.domain.session;

import java.util.Objects;

/** Mutable state available to an action while processing one request. */
public final class SessionContext {

  private final Session session;

  public SessionContext(Session session) {
    this.session = Objects.requireNonNull(session, "session");
  }

  public Session getSession() {
    return session;
  }

  public void clearHistory() {
    session.clearHistory();
  }

  public void updatePreferences(Preferences preferences) {
    session.setPreferences(preferences);
  }
}
