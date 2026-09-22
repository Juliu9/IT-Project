package com.gen3.recommenderagent.storage.sessionrepository;

import com.gen3.recommenderagent.engine.events.SessionUpdatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class SessionUpdateObserver {

  private final SessionRepository sessionRepository;

  public SessionUpdateObserver(SessionRepository sessionRepository) {
    this.sessionRepository = sessionRepository;
  }

  @Async
  @EventListener
  public void onSessionUpdated(SessionUpdatedEvent event) {
    sessionRepository.updateSession(event.getSession());
  }
}
