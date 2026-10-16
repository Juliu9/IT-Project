package com.gen3.recommenderagent.storage.sessionrepository;

import com.gen3.recommenderagent.domain.session.Session;
import java.util.function.Supplier;

public interface SessionRepository {

  Session getSession(String sessionId, String userId);

  boolean updateSession(Session session);

  <T> T withSessionLock(String sessionId, String userId, Supplier<T> operation);
}
