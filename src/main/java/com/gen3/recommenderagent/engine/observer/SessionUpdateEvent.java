package com.gen3.recommenderagent.engine.observer;

import com.gen3.recommenderagent.domain.session.Session;

public class SessionUpdateEvent {
    private final Session session;

    public SessionUpdateEvent(Session session) {
        this.session = session;
    }

    public Session getSession() {
        return session;
    }
}