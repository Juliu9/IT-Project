package com.gen3.recommenderagent.engine;

import com.gen3.recommenderagent.domain.session.Recommendations;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.observer.SessionSubject;
import com.gen3.recommenderagent.storage.sessioncache.SessionCache;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class RecommendationEngine {

    private final SessionCache sessionCache;
    private final SessionSubject sessionSubject;

    public RecommendationEngine(SessionCache sessionCache, SessionSubject sessionSubject) {
        this.sessionCache = sessionCache;
        this.sessionSubject = sessionSubject;
    }

    public Recommendations process(String sessionId, String userId, SessionRequest currentRequest) {
        // 1. Read current session from Redis
        Session session = sessionCache.getSession(sessionId);
        if (session == null) {
            session = new Session();
            session.setSessionId(sessionId);
            session.setUserId(userId);
            session.setCreatedAt(Instant.now());
        }

        // 2. Use session history + currentRequest to resolve context

        // 3. Make recommendation decision (Search & Ranking Pipeline)
        Recommendations recommendations = new Recommendations();

        // 4. Attach results to the current request and update session
        currentRequest.setRequestId(UUID.randomUUID().toString());
        currentRequest.setRecommendations(recommendations);
        currentRequest.setCreatedAt(Instant.now());
        session.addRequest(currentRequest);

        // 5. Notify all attached observers (e.g., Redis, Postgres)
        // This triggers the async methods on the observers without blocking
        sessionSubject.notifyObservers(session);

        return recommendations;
    }
}