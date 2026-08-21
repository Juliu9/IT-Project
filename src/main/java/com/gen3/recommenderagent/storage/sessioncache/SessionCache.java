package com.gen3.recommenderagent.storage.sessioncache;

import com.gen3.recommenderagent.domain.session.Session;

public interface SessionCache {

    Session getSession(String sessionId);

    void updateSession(Session session);

}
