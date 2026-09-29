package com.gen3.recommenderagent.application.sessionservice;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;

/** Owns loading and persisting conversational state. */
public interface SessionService {

  Session load(String sessionId, String userId);

  void update(Session session, SessionRequest originalRequest, SessionRequest actionResult);
}
