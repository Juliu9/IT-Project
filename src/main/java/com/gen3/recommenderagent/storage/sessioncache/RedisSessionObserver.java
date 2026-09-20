package com.gen3.recommenderagent.storage.sessioncache;

import com.gen3.recommenderagent.engine.SessionUpdateEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class RedisSessionObserver {

  private final SessionCache sessionCache;

  public RedisSessionObserver(SessionCache sessionCache) {
    this.sessionCache = sessionCache;
  }

  @Async
  @EventListener
  public void onSessionUpdated(SessionUpdateEvent event) {
    sessionCache.updateSession(event.getSession());
  }
}
