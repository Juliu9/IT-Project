package com.gen3.recommenderagent.engine.events;

import com.gen3.recommenderagent.domain.session.Session;

public class SessionUpdatedEvent implements DomainEvent {
    private final Session session;

    public SessionUpdatedEvent(Session session) {
        this.session = session;
    }

    public Session getSession() {
        return session;
    }
}