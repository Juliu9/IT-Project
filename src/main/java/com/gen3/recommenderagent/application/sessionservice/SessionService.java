package com.gen3.recommenderagent.application.sessionservice;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.function.Supplier;

/** Owns loading and persisting conversational state. */
public interface SessionService {

  Session load(String sessionId, String userId);

  boolean update(Session session, SessionRequest originalRequest, SessionRequest actionResult);

  <T> T withSessionLock(String sessionId, String userId, Supplier<T> operation);
}
