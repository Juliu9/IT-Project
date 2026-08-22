package com.gen3.recommenderagent.storage.sessioncache;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.engine.observer.SessionObserver;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class RedisSessionObserver implements SessionObserver {

    private final SessionCache sessionCache;

    public RedisSessionObserver(SessionCache sessionCache) {
        this.sessionCache = sessionCache;
    }

    @Async
    @Override
    public void onSessionUpdated(Session session) {
        // This executes in a separate thread pool, acting as our lightweight queue.
        // The main request thread does not wait for this Redis write to finish.
        sessionCache.updateSession(session);
    }
}