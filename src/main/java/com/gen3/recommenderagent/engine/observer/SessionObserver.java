package com.gen3.recommenderagent.engine.observer;

import com.gen3.recommenderagent.domain.session.Session;

/**
 * Contract for any component that needs to react to session updates.
 */
public interface SessionObserver {
    void onSessionUpdated(Session session);
}