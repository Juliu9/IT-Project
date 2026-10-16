package com.gen3.recommenderagent.application.sessionservice;

import com.gen3.recommenderagent.domain.session.Session;
import java.util.function.Supplier;

/** Stores session state while the session-transition module owns its mutation rules. */
public interface SessionService {

  Session load(String sessionId, String userId);

  boolean save(Session session);

  <T> T withSessionLock(String sessionId, String userId, Supplier<T> operation);
}
