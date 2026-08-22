package com.gen3.recommenderagent.engine.observer;

import com.gen3.recommenderagent.domain.session.Session;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SessionUpdateManager implements SessionSubject {

    // Thread-safe list in case observers are attached/detached dynamically
    private final List<SessionObserver> observers;

    // Spring automatically injects all implementations of SessionObserver
    public SessionUpdateManager(List<SessionObserver> observers) {
        this.observers = new CopyOnWriteArrayList<>(observers);
    }

    @Override
    public void attach(SessionObserver observer) {
        if (!observers.contains(observer)) {
            observers.add(observer);
        }
    }

    @Override
    public void detach(SessionObserver observer) {
        observers.remove(observer);
    }

    @Override
    public void notifyObservers(Session session) {
        for (SessionObserver observer : observers) {
            observer.onSessionUpdated(session);
        }
    }
}