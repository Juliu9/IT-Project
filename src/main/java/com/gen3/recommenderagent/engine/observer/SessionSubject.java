package com.gen3.recommenderagent.engine.observer;

import com.gen3.recommenderagent.domain.session.Session;

/**
 * Contract for the subject that manages and notifies observers.
 */
public interface SessionSubject {
    void attach(SessionObserver observer);
    void detach(SessionObserver observer);
    void notifyObservers(Session session);
}