package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;

/** Owns loading and persisting conversational state. */
public interface SessionService {

  SessionContext load(String sessionId, String userId);

  void update(
      SessionContext context, SessionRequest originalRequest, SessionRequest actionResult);
}
