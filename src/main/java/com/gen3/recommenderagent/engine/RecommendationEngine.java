package com.gen3.recommenderagent.engine;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.events.SessionUpdatedEvent;
import com.gen3.recommenderagent.engine.handlers.IntentHandlerFactory;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import com.gen3.recommenderagent.engine.events.DomainEventPublisher;
import org.springframework.stereotype.Service;
import com.gen3.recommenderagent.engine.handlers.IntentHandler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RecommendationEngine {
    private final SessionRepository sessionCache;
    private final DomainEventPublisher eventPublisher;
    private final IntentHandlerFactory intentHandlerFactory;

    public RecommendationEngine(
            SessionRepository sessionCache,
            DomainEventPublisher eventPublisher,
            IntentHandlerFactory intentHandlerFactory

    ) {
        this.sessionCache = sessionCache;
        this.eventPublisher = eventPublisher;
        this.intentHandlerFactory = intentHandlerFactory;

    }

    public List<Recommendation> process(
            String sessionId,
            String userId,
            SessionRequest currentRequest
    ) {

        // ==========================================
        // 1. Read current session from Redis
        // ==========================================

        Session session = sessionCache.getSession(sessionId);

        if (session == null) {
            session = new Session();
            session.setSessionId(sessionId);
            session.setUserId(userId);
            session.setCreatedAt(Instant.now());
        }

        // ==========================================
        // 2. Resolve context
        // ==========================================

        Intent intent = currentRequest.getIntent();
        if (intent == null) {
            intent = Intent.UNKNOWN;
            currentRequest.setIntent(intent);
        }

        // TODO:
        // Use session history + currentRequest to resolve
        // additional context required by the intent.

        /*
        USE PREFERENCES TO BIAS WEIGHTS WHEN RANKING RECOMMENDATIONS

        List<Preference> userPreferences;
        if currentRequest.isPersonalised() {
            userPreferences = userProfileDB.getReferenceById(userId).getPreferences();
        }
         */

        // ==========================================
        // 3. Handle request based on intent
        // ==========================================

        List<Recommendation> recommendations;

        IntentHandler handler = intentHandlerFactory.getHandler(intent);
        recommendations = (handler != null)
                ? handler.handle(currentRequest, session)
                : new ArrayList<>();
        session.addShownBooks(recommendations);

        // ==========================================
        // 4. Attach results to request + update session
        // ==========================================

        currentRequest.setRequestId(UUID.randomUUID().toString());
        currentRequest.setRecommendations(recommendations);
        currentRequest.setCreatedAt(Instant.now());

        session.addRequest(currentRequest);


        // ==========================================
        // 5. Notify observers
        // ==========================================

        eventPublisher.publish(
                new SessionUpdatedEvent(session)
        );

        return recommendations;
    }
}