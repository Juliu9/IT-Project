package com.gen3.recommenderagent.storage.sessionrepository;

import com.gen3.recommenderagent.domain.session.Session;

public interface SessionRepository {

  Session getSession(String sessionId, String userId);

  void updateSession(Session session);
}
