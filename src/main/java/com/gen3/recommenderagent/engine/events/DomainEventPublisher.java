package com.gen3.recommenderagent.engine.events;

public interface DomainEventPublisher {
    void publish(DomainEvent event);
}